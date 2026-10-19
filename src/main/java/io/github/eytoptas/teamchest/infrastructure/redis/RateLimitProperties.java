package io.github.eytoptas.teamchest.infrastructure.redis;

import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("team-chest.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        Limit join,
        Limit progress,
        Limit claim,
        Limit get) {

    public RateLimitProperties {
        Objects.requireNonNull(join, "team-chest.rate-limit.join must be configured");
        Objects.requireNonNull(progress, "team-chest.rate-limit.progress must be configured");
        Objects.requireNonNull(claim, "team-chest.rate-limit.claim must be configured");
        Objects.requireNonNull(get, "team-chest.rate-limit.get must be configured");
    }

    public Limit limitFor(RateLimitedOperation operation) {
        return switch (operation) {
            case JOIN -> join;
            case PROGRESS -> progress;
            case CLAIM -> claim;
            case GET -> get;
        };
    }

    public record Limit(@DefaultValue("true") boolean enabled, int capacity, double refillPerSecond) {

        public Limit {
            if (capacity < 1) {
                throw new IllegalArgumentException("Rate limit capacity must be at least 1");
            }
            if (!(refillPerSecond > 0) || Double.isInfinite(refillPerSecond)) {
                throw new IllegalArgumentException("Rate limit refill-per-second must be a positive number");
            }
        }
    }
}
