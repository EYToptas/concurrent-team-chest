package io.github.eytoptas.teamchest.application.join;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("team-chest.join")
public record JoinProperties(
        @DefaultValue("true") boolean batching,
        @DefaultValue("50") int maxBatchSize,
        @DefaultValue("500") int queueCapacity,
        @DefaultValue("5s") Duration queueTimeout,
        @DefaultValue("30s") Duration resultTimeout) {

    public JoinProperties {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("team-chest.join.max-batch-size must be at least 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("team-chest.join.queue-capacity must be at least 1");
        }
        if (!queueTimeout.isPositive() || !resultTimeout.isPositive()) {
            throw new IllegalArgumentException("team-chest.join timeouts must be positive");
        }
    }
}
