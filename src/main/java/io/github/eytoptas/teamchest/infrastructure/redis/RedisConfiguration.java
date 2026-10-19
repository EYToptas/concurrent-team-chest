package io.github.eytoptas.teamchest.infrastructure.redis;

import io.lettuce.core.ClientOptions;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RedisFailoverProperties.class, CacheProperties.class, RateLimitProperties.class})
public class RedisConfiguration {

    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return options -> options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    @Bean
    RedisCircuitBreaker redisCircuitBreaker(Clock clock, RedisFailoverProperties properties) {
        return new RedisCircuitBreaker(clock, properties.failureCooldown());
    }
}
