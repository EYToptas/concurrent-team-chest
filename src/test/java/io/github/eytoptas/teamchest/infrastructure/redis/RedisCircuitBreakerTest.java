package io.github.eytoptas.teamchest.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisCircuitBreakerTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(2);
    private static final String FALLBACK = "fallback";

    private final MutableClock clock = new MutableClock();
    private final RedisCircuitBreaker breaker = new RedisCircuitBreaker(clock, COOLDOWN);
    private final AtomicInteger redisCalls = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void successfulCallReturnsItsResult() {
        assertThat(breaker.execute("read", redisReturning("value"), FALLBACK)).isEqualTo("value");
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void failingCallReturnsTheFallbackInsteadOfThrowing() {
        assertThat(breaker.execute("read", failingRedis(), FALLBACK)).isEqualTo(FALLBACK);
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void afterAFailureRedisIsSkippedForTheCooldown() {
        breaker.execute("read", failingRedis(), FALLBACK);
        redisCalls.set(0);

        clock.advance(COOLDOWN.minusMillis(1));
        assertThat(breaker.execute("read", redisReturning("value"), FALLBACK)).isEqualTo(FALLBACK);
        assertThat(redisCalls).as("no request waits for a failing Redis during the cooldown").hasValue(0);
    }

    @Test
    void successfulProbeAfterTheCooldownClosesTheBreaker() {
        breaker.execute("read", failingRedis(), FALLBACK);
        clock.advance(COOLDOWN);

        assertThat(breaker.execute("read", redisReturning("value"), FALLBACK)).isEqualTo("value");
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.execute("read", redisReturning("next"), FALLBACK)).isEqualTo("next");
    }

    @Test
    void failedProbeSkipsRedisForAnotherCooldown() {
        breaker.execute("read", failingRedis(), FALLBACK);
        clock.advance(COOLDOWN);
        assertThat(breaker.execute("read", failingRedis(), FALLBACK)).isEqualTo(FALLBACK);
        redisCalls.set(0);

        clock.advance(COOLDOWN.minusMillis(1));
        assertThat(breaker.execute("read", redisReturning("value"), FALLBACK)).isEqualTo(FALLBACK);
        assertThat(redisCalls).hasValue(0);
        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.execute("read", redisReturning("value"), FALLBACK)).isEqualTo("value");
    }

    @Test
    void whileOneCallerProbesTheOthersUseTheFallback() throws Exception {
        breaker.execute("read", failingRedis(), FALLBACK);
        clock.advance(COOLDOWN);
        CountDownLatch probing = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);

        Future<String> probe = executor.submit(() -> breaker.execute("read", () -> {
            probing.countDown();
            await(releaseProbe);
            return "probe";
        }, FALLBACK));
        await(probing);
        redisCalls.set(0);

        assertThat(breaker.execute("read", redisReturning("other"), FALLBACK)).isEqualTo(FALLBACK);
        assertThat(redisCalls).hasValue(0);

        releaseProbe.countDown();
        assertThat(probe.get(5, TimeUnit.SECONDS)).isEqualTo("probe");
        assertThat(breaker.execute("read", redisReturning("other"), FALLBACK)).isEqualTo("other");
    }

    @Test
    void worksWithTheApplicationsMicrosecondClock() {
        Clock applicationClock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
        RedisCircuitBreaker withApplicationClock = new RedisCircuitBreaker(applicationClock, COOLDOWN);

        assertThat(withApplicationClock.execute("read", failingRedis(), FALLBACK)).isEqualTo(FALLBACK);
        assertThat(withApplicationClock.isOpen()).isTrue();
        assertThat(withApplicationClock.execute("read", redisReturning("value"), FALLBACK)).isEqualTo(FALLBACK);
    }

    private Supplier<String> redisReturning(String value) {
        return () -> {
            redisCalls.incrementAndGet();
            return value;
        };
    }

    private Supplier<String> failingRedis() {
        return () -> {
            redisCalls.incrementAndGet();
            throw new IllegalStateException("Redis command timed out");
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-10-18T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
