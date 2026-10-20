package io.github.eytoptas.teamchest.infrastructure;

import io.github.eytoptas.teamchest.application.TeamChestProperties;
import io.github.eytoptas.teamchest.application.join.JoinProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TeamChestProperties.class, JoinProperties.class})
public class ApplicationConfiguration {

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
