package io.github.eytoptas.teamchest.infrastructure.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("team-chest.redis")
public record RedisFailoverProperties(@DefaultValue("2s") Duration failureCooldown) {

    public RedisFailoverProperties {
        if (failureCooldown.isNegative() || failureCooldown.isZero()) {
            throw new IllegalArgumentException("team-chest.redis.failure-cooldown must be positive");
        }
    }
}
