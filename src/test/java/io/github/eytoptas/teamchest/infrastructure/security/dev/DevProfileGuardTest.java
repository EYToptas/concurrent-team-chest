package io.github.eytoptas.teamchest.infrastructure.security.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DevProfileGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DevJwtConfiguration.class);

    @Test
    void refusesToStartTogetherWithTheProdProfile() {
        runner.withPropertyValues("spring.profiles.active=dev,prod")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("prod profile"));
    }

    @Test
    void createsNothingOutsideTheDevProfile() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(DevTokenIssuer.class)
                        .doesNotHaveBean(KeyPair.class));
    }

    @Test
    void createsNothingWithoutAnyProfile() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(DevTokenIssuer.class));
    }
}
