package io.github.eytoptas.teamchest.infrastructure.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("team-chest.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration teamTtl,
        @DefaultValue("10m") Duration membershipTtl) {

    public CacheProperties {
        requirePositive(teamTtl, "team-chest.cache.team-ttl");
        requirePositive(membershipTtl, "team-chest.cache.membership-ttl");
    }

    private static void requirePositive(Duration duration, String property) {
        if (duration.toMillis() <= 0) {
            throw new IllegalArgumentException(property + " must be at least 1 ms");
        }
    }
}
