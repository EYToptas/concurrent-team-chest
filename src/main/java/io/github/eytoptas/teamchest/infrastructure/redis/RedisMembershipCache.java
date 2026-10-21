package io.github.eytoptas.teamchest.infrastructure.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
    private final Cache<LocalKey, Long> local;

    RedisMembershipCache(StringRedisTemplate redis, CacheProperties properties, RedisCircuitBreaker circuitBreaker) {
        this.redis = redis;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.local = properties.membershipLocalEntries() == 0 ? null : Caffeine.newBuilder()
                .maximumSize(properties.membershipLocalEntries())
                .expireAfterWrite(properties.membershipTtl())
                .build();
    }

    @Override
    public Optional<Long> findTeamId(long campaignId, String userId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        LocalKey localKey = new LocalKey(campaignId, userId);
        if (local != null) {
            Long teamId = local.getIfPresent(localKey);
            if (teamId != null) {
                return Optional.of(teamId);
            }
        }
        Optional<Long> stored = readRedis(campaignId, userId);
        if (local != null) {
            stored.ifPresent(teamId -> local.put(localKey, teamId));
        }
        return stored;
    }

    @Override
    public void remember(long campaignId, String userId, long teamId) {
        if (!properties.enabled()) {
            return;
        }
        if (local != null) {
            local.put(new LocalKey(campaignId, userId), teamId);
        }
        String key = RedisKeys.membership(campaignId, userId);
        circuitBreaker.execute("membership write", () -> {
            redis.opsForValue().set(key, Long.toString(teamId), properties.membershipTtl());
            return null;
        }, null);
    }

    private Optional<Long> readRedis(long campaignId, String userId) {
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

    private record LocalKey(long campaignId, String userId) {
    }
}
