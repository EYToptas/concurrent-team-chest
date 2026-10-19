package io.github.eytoptas.teamchest.infrastructure.security;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

final class AccessTokenRules {

    static final int MAX_SUBJECT_LENGTH = 64;

    private AccessTokenRules() {
    }

    static OAuth2TokenValidator<Jwt> validator(JwtProperties properties) {
        Predicate<Instant> present = Objects::nonNull;
        Predicate<List<String>> forThisApi = audience -> audience != null && audience.contains(properties.audience());
        Predicate<String> usableSubject = subject ->
                subject != null && !subject.isBlank() && subject.length() <= MAX_SUBJECT_LENGTH;

        List<OAuth2TokenValidator<Jwt>> rules = List.of(
                new JwtTimestampValidator(properties.clockSkew()),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, present),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, forThisApi),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, usableSubject));
        return new DelegatingOAuth2TokenValidator<>(rules);
    }
}
