package io.github.eytoptas.teamchest.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RedisPropertiesTest {

    private static final RateLimitProperties.Limit LIMIT = new RateLimitProperties.Limit(true, 5, 1);

    @Test
    void eachEndpointTypeHasItsOwnLimit() {
        RateLimitProperties.Limit join = new RateLimitProperties.Limit(true, 1, 1);
        RateLimitProperties.Limit progress = new RateLimitProperties.Limit(true, 2, 1);
        RateLimitProperties.Limit claim = new RateLimitProperties.Limit(false, 3, 1);
        RateLimitProperties.Limit get = new RateLimitProperties.Limit(true, 4, 0.5);
        RateLimitProperties properties = new RateLimitProperties(true, join, progress, claim, get);

        assertThat(properties.limitFor(RateLimitedOperation.JOIN)).isSameAs(join);
        assertThat(properties.limitFor(RateLimitedOperation.PROGRESS)).isSameAs(progress);
        assertThat(properties.limitFor(RateLimitedOperation.CLAIM)).isSameAs(claim);
        assertThat(properties.limitFor(RateLimitedOperation.GET)).isSameAs(get);
    }

    @Test
    void everyEndpointTypeMustBeConfigured() {
        assertThatThrownBy(() -> new RateLimitProperties(true, LIMIT, null, LIMIT, LIMIT))
                .hasMessageContaining("team-chest.rate-limit.progress");
    }

    @Test
    void limitsMustAllowAtLeastOneRequestAndRefill() {
        assertThatThrownBy(() -> new RateLimitProperties.Limit(true, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(true, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(true, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(true, 1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cacheLifetimesMustBePositive() {
        assertThatThrownBy(() -> new CacheProperties(true, Duration.ZERO, Duration.ofMinutes(10), 100))
                .hasMessageContaining("team-ttl");
        assertThatThrownBy(() -> new CacheProperties(true, Duration.ofSeconds(30), Duration.ofSeconds(-1), 100))
                .hasMessageContaining("membership-ttl");
    }

    @Test
    void localMembershipEntriesMustNotBeNegative() {
        assertThatThrownBy(() -> new CacheProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(10), -1))
                .hasMessageContaining("membership-local-entries");
    }

    @Test
    void failureCooldownMustBePositive() {
        assertThatThrownBy(() -> new RedisFailoverProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
