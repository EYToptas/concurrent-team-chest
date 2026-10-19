package io.github.eytoptas.teamchest.infrastructure.redis;

import io.github.eytoptas.teamchest.application.cache.TeamStandingCache;
import io.github.eytoptas.teamchest.application.query.TeamOverview.TeamStanding;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class RedisTeamStandingCache implements TeamStandingCache {

    private static final Logger log = LoggerFactory.getLogger(RedisTeamStandingCache.class);
    private static final RedisScript<Long> FILL =
            RedisScript.of(new ClassPathResource("redis/team-standing-fill.lua"), Long.class);
    private static final RedisScript<Long> INVALIDATE =
            RedisScript.of(new ClassPathResource("redis/team-standing-invalidate.lua"), Long.class);
    private static final String VERSION = "v";
    private static final String DATA = "d";
    private static final List<Object> FIELDS = List.of(VERSION, DATA);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final CacheProperties properties;
    private final RedisCircuitBreaker circuitBreaker;

    RedisTeamStandingCache(StringRedisTemplate redis, JsonMapper jsonMapper, CacheProperties properties,
                           RedisCircuitBreaker circuitBreaker) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Optional<TeamStanding> find(long campaignId, long teamId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        String key = RedisKeys.team(campaignId, teamId);
        List<Object> fields = circuitBreaker.execute("cache read", () -> redis.opsForHash().multiGet(key, FIELDS), null);
        if (fields == null || fields.get(1) == null) {
            return Optional.empty();
        }
        return decode(key, teamId, (String) fields.get(0), (String) fields.get(1));
    }

    @Override
    public void offer(long campaignId, TeamStanding standing) {
        if (!properties.enabled()) {
            return;
        }
        String json = encode(standing);
        if (json == null) {
            return;
        }
        List<String> keys = List.of(RedisKeys.team(campaignId, standing.teamId()));
        circuitBreaker.execute("cache fill", () -> redis.execute(FILL, keys,
                Long.toString(standing.version()), json, ttlMillis()), null);
    }

    @Override
    public void invalidate(long campaignId, long teamId, long committedVersion) {
        List<String> keys = List.of(RedisKeys.team(campaignId, teamId));
        circuitBreaker.execute("cache invalidation", () -> redis.execute(INVALIDATE, keys,
                Long.toString(committedVersion), ttlMillis()), null);
    }

    private String ttlMillis() {
        return Long.toString(properties.teamTtl().toMillis());
    }

    private String encode(TeamStanding standing) {
        try {
            return jsonMapper.writeValueAsString(standing);
        } catch (RuntimeException e) {
            log.warn("Could not serialize team {} for the cache; it is served without caching", standing.teamId(), e);
            return null;
        }
    }

    private Optional<TeamStanding> decode(String key, long teamId, String version, String json) {
        try {
            TeamStanding standing = jsonMapper.readValue(json, TeamStanding.class);
            if (standing.teamId() == teamId && standing.version() == Long.parseLong(version)) {
                return Optional.of(standing);
            }
            log.warn("Ignoring cache entry {}: its content does not match the key or the version", key);
        } catch (RuntimeException e) {
            log.warn("Ignoring unreadable cache entry {}: {}", key, e.toString());
        }
        return Optional.empty();
    }
}
