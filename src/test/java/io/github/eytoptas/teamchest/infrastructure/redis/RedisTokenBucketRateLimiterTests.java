package io.github.eytoptas.teamchest.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eytoptas.teamchest.TestcontainersConfiguration;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimitDecision;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

class RedisTokenBucketRateLimiterTests extends PersistenceIntegrationTest {

    private static final RateLimitProperties.Limit GENEROUS = new RateLimitProperties.Limit(true, 1_000, 1_000);
    private static final double NO_REFILL = 0.001;

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private Clock clock;

    private final String user = "user-" + UUID.randomUUID();

    @Test
    void aBurstUpToTheCapacityIsAllowedThenTheNextRequestIsRejected() {
        RedisTokenBucketRateLimiter limiter = limiter(redis, progressLimit(3, NO_REFILL));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(RateLimitedOperation.PROGRESS, user).allowed()).isTrue();
        }
        RateLimitDecision rejected = limiter.tryAcquire(RateLimitedOperation.PROGRESS, user);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isBetween(Duration.ofSeconds(990), Duration.ofSeconds(1000));
    }

    @Test
    void concurrentRequestsNeverTakeMoreTokensThanTheCapacity() throws Exception {
        RedisTokenBucketRateLimiter limiter = limiter(redis, progressLimit(10, NO_REFILL));

        List<RateLimitDecision> decisions = runConcurrently(64,
                () -> limiter.tryAcquire(RateLimitedOperation.PROGRESS, user));

        assertThat(decisions).filteredOn(RateLimitDecision::allowed).hasSize(10);
    }

    @Test
    void applicationInstancesShareOneBucket() throws Exception {
        GenericContainer<?> redisContainer = TestcontainersConfiguration.redis();
        LettuceConnectionFactory secondInstanceConnection = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        secondInstanceConnection.afterPropertiesSet();
        secondInstanceConnection.start();
        try {
            RedisTokenBucketRateLimiter instanceA = limiter(redis, progressLimit(10, NO_REFILL));
            RedisTokenBucketRateLimiter instanceB =
                    limiter(new StringRedisTemplate(secondInstanceConnection), progressLimit(10, NO_REFILL));
            List<Supplier<RateLimitDecision>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                requests.add(() -> instanceA.tryAcquire(RateLimitedOperation.PROGRESS, user));
                requests.add(() -> instanceB.tryAcquire(RateLimitedOperation.PROGRESS, user));
            }

            List<RateLimitDecision> decisions = runConcurrently(requests);

            assertThat(decisions).filteredOn(RateLimitDecision::allowed).hasSize(10);
        } finally {
            secondInstanceConnection.destroy();
        }
    }

    @Test
    void tokensComeBackAtTheRefillRate() throws Exception {
        RedisTokenBucketRateLimiter limiter = limiter(redis, progressLimit(1, 20));

        assertThat(limiter.tryAcquire(RateLimitedOperation.PROGRESS, user).allowed()).isTrue();
        RateLimitDecision rejected = limiter.tryAcquire(RateLimitedOperation.PROGRESS, user);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isBetween(Duration.ofMillis(1), Duration.ofMillis(50));

        Thread.sleep(rejected.retryAfter().plusMillis(30).toMillis());
        assertThat(limiter.tryAcquire(RateLimitedOperation.PROGRESS, user).allowed()).isTrue();
    }

    @Test
    void bucketsArePerUserAndPerEndpointType() {
        RateLimitedOperation progress = RateLimitedOperation.PROGRESS;
        RedisTokenBucketRateLimiter limiter = limiter(redis, progressLimit(1, NO_REFILL));
        limiter.tryAcquire(progress, user);
        assertThat(limiter.tryAcquire(progress, user).allowed()).isFalse();

        assertThat(limiter.tryAcquire(progress, user + "-other").allowed()).as("other user").isTrue();
        assertThat(limiter.tryAcquire(RateLimitedOperation.GET, user).allowed()).as("other endpoint").isTrue();
    }

    @Test
    void idleBucketsExpireOnceTheyWouldBeFull() {
        RedisTokenBucketRateLimiter limiter = limiter(redis, progressLimit(4, 2));

        limiter.tryAcquire(RateLimitedOperation.PROGRESS, user);

        assertThat(redis.getExpire(RedisKeys.rateLimit(RateLimitedOperation.PROGRESS, user), TimeUnit.MILLISECONDS))
                .isBetween(1L, 3_000L);
    }

    @Test
    void disabledLimitsAllowEverythingWithoutRedis() {
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(clock, Duration.ofSeconds(1));
        RateLimitProperties.Limit tight = new RateLimitProperties.Limit(true, 1, NO_REFILL);
        RedisTokenBucketRateLimiter globallyOff = new RedisTokenBucketRateLimiter(redis,
                new RateLimitProperties(false, tight, tight, tight, tight), breaker);
        RateLimitProperties.Limit off = new RateLimitProperties.Limit(false, 1, NO_REFILL);
        RedisTokenBucketRateLimiter progressOff = limiter(redis, off);

        for (int i = 0; i < 3; i++) {
            assertThat(globallyOff.tryAcquire(RateLimitedOperation.GET, user).allowed()).isTrue();
            assertThat(progressOff.tryAcquire(RateLimitedOperation.PROGRESS, user).allowed()).isTrue();
        }
        assertThat(redis.hasKey(RedisKeys.rateLimit(RateLimitedOperation.GET, user))).isFalse();
        assertThat(redis.hasKey(RedisKeys.rateLimit(RateLimitedOperation.PROGRESS, user))).isFalse();
    }

    @Test
    void unreachableRedisFailsOpen() {
        try (UnreachableRedis unreachable = new UnreachableRedis(clock)) {
            RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(unreachable.template,
                    properties(progressLimit(1, NO_REFILL)), unreachable.circuitBreaker);

            for (int i = 0; i < 3; i++) {
                assertThat(limiter.tryAcquire(RateLimitedOperation.PROGRESS, user).allowed()).isTrue();
            }
            assertThat(unreachable.circuitBreaker.isOpen()).isTrue();
        }
    }

    private static RateLimitProperties.Limit progressLimit(int capacity, double refillPerSecond) {
        return new RateLimitProperties.Limit(true, capacity, refillPerSecond);
    }

    private static RateLimitProperties properties(RateLimitProperties.Limit progress) {
        return new RateLimitProperties(true, GENEROUS, progress, GENEROUS, GENEROUS);
    }

    private RedisTokenBucketRateLimiter limiter(StringRedisTemplate template, RateLimitProperties.Limit progress) {
        return new RedisTokenBucketRateLimiter(template, properties(progress),
                new RedisCircuitBreaker(clock, Duration.ofSeconds(1)));
    }

    private static <T> List<T> runConcurrently(int times, Supplier<T> request) throws Exception {
        List<Supplier<T>> requests = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            requests.add(request);
        }
        return runConcurrently(requests);
    }

    private static <T> List<T> runConcurrently(List<Supplier<T>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Supplier<T> request : requests) {
                futures.add(pool.submit(() -> {
                    await(start);
                    return request.get();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
