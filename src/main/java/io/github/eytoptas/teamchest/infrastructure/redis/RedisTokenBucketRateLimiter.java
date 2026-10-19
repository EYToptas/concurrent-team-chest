package io.github.eytoptas.teamchest.infrastructure.redis;

import io.github.eytoptas.teamchest.application.ratelimit.RateLimitDecision;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimiter;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
class RedisTokenBucketRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET =
            RedisScript.of(new ClassPathResource("redis/token-bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final RedisCircuitBreaker circuitBreaker;

    RedisTokenBucketRateLimiter(StringRedisTemplate redis, RateLimitProperties properties,
                                RedisCircuitBreaker circuitBreaker) {
        this.redis = redis;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public RateLimitDecision tryAcquire(RateLimitedOperation operation, String userId) {
        RateLimitProperties.Limit limit = properties.limitFor(operation);
        if (!properties.enabled() || !limit.enabled()) {
            return RateLimitDecision.allow();
        }
        List<String> keys = List.of(RedisKeys.rateLimit(operation, userId));
        String capacity = Integer.toString(limit.capacity());
        String refillPerSecond = BigDecimal.valueOf(limit.refillPerSecond()).toPlainString();
        List<?> result = circuitBreaker.execute("rate limit",
                () -> redis.execute(TOKEN_BUCKET, keys, capacity, refillPerSecond), null);
        if (result == null) {
            return RateLimitDecision.allow();
        }
        if (((Number) result.get(0)).longValue() == 1) {
            return RateLimitDecision.allow();
        }
        long retryAfterMillis = Math.max(1, ((Number) result.get(1)).longValue());
        return RateLimitDecision.reject(Duration.ofMillis(retryAfterMillis));
    }
}
