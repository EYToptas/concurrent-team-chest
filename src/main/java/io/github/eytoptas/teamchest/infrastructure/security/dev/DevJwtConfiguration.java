package io.github.eytoptas.teamchest.infrastructure.security.dev;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.github.eytoptas.teamchest.infrastructure.security.JwtProperties;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevJwtConfiguration {

    public DevJwtConfiguration(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("The dev token issuer must not run with the prod profile");
        }
    }

    @Bean
    KeyPair devJwtKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is required by every Java platform", e);
        }
    }

    @Bean
    RSAPublicKey jwtVerificationKey(KeyPair devJwtKeyPair) {
        return (RSAPublicKey) devJwtKeyPair.getPublic();
    }

    @Bean
    JwtEncoder devJwtEncoder(KeyPair devJwtKeyPair) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) devJwtKeyPair.getPublic())
                .privateKey(devJwtKeyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    DevTokenIssuer devTokenIssuer(JwtEncoder devJwtEncoder, JwtProperties properties, Clock clock) {
        return new DevTokenIssuer(devJwtEncoder, properties, clock);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain devTokenEndpointSecurity(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/dev/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }
}
