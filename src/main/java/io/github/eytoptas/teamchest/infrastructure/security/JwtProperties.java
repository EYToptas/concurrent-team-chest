package io.github.eytoptas.teamchest.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("team-chest.security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        Resource publicKeyLocation,
        @DefaultValue("30s") Duration clockSkew,
        @DefaultValue("1h") Duration devTokenTtl) {
}
