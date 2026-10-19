package io.github.eytoptas.teamchest.infrastructure.redis;

import io.github.eytoptas.teamchest.application.cache.MembershipCache;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisMembershipCache implements MembershipCache {

    private static final Logger log = LoggerFactory.getLogger(RedisMembershipCache.class);

    private final StringRedisTemplate redis;
    private final CacheProperties properties;
    private final RedisCircuitBreaker circuitBreaker;

    RedisMembershipCache(StringRedisTemplate redis, CacheProperties properties, RedisCircuitBreaker circuitBreaker) {
        this.redis = redis;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Optional<Long> findTeamId(long campaignId, String userId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        String key = RedisKeys.membership(campaignId, userId);
        String teamId = circuitBreaker.execute("membership read", () -> redis.opsForValue().get(key), null);
        if (teamId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(teamId));
        } catch (NumberFormatException e) {
            log.warn("Ignoring unreadable cache entry {}", key);
            return Optional.empty();
        }
    }

    @Override
    public void remember(long campaignId, String userId, long teamId) {
        if (!properties.enabled()) {
            return;
        }
        String key = RedisKeys.membership(campaignId, userId);
        circuitBreaker.execute("membership write", () -> {
            redis.opsForValue().set(key, Long.toString(teamId), properties.membershipTtl());
            return null;
        }, null);
    }
}
