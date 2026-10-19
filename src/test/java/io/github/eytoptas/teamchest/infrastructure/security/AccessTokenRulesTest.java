package io.github.eytoptas.teamchest.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class AccessTokenRulesTest {

    private static final String ISSUER = "https://auth.example.com";
    private static final String AUDIENCE = "team-chest-api";

    private final OAuth2TokenValidator<Jwt> rules = AccessTokenRules.validator(
            new JwtProperties(ISSUER, AUDIENCE, null, Duration.ofSeconds(30), Duration.ofHours(1)));

    @Test
    void acceptsATokenWithAllRequiredClaims() {
        assertThat(rules.validate(token(builder -> builder)).hasErrors()).isFalse();
    }

    @Test
    void rejectsAnotherIssuer() {
        assertRejected(builder -> builder.issuer("https://evil.example"));
    }

    @Test
    void rejectsAnotherAudience() {
        assertRejected(builder -> builder.audience(List.of("other-api")));
    }

    @Test
    void rejectsAnExpiredToken() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        assertRejected(builder -> builder.issuedAt(past).expiresAt(past.plus(Duration.ofHours(1))));
    }

    @Test
    void toleratesTheConfiguredClockSkew() {
        Instant justExpired = Instant.now().minusSeconds(10);
        assertThat(rules.validate(token(builder -> builder.expiresAt(justExpired))).hasErrors()).isFalse();
    }

    @Test
    void rejectsATokenThatNeverExpires() {
        assertRejected(builder -> builder.claims(claims -> claims.remove("exp")));
    }

    @Test
    void rejectsAMissingBlankOrTooLongSubject() {
        assertRejected(builder -> builder.claims(claims -> claims.remove("sub")));
        assertRejected(builder -> builder.subject(" "));
        assertRejected(builder -> builder.subject("u".repeat(65)));
    }

    private void assertRejected(UnaryOperator<Jwt.Builder> change) {
        assertThat(rules.validate(token(change)).hasErrors()).isTrue();
    }

    private static Jwt token(UnaryOperator<Jwt.Builder> change) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject("alice")
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(3600));
        return change.apply(builder).build();
    }
}
