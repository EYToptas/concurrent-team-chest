package io.github.eytoptas.teamchest.infrastructure.security;

import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!dev")
class TrustedIssuerKeyConfiguration {

    @Bean
    RSAPublicKey jwtVerificationKey(JwtProperties properties) {
        if (properties.publicKeyLocation() == null) {
            throw new IllegalStateException("team-chest.security.jwt.public-key-location must point to the "
                    + "trusted issuer's RSA public key (PEM)");
        }
        return PemPublicKeys.read(properties.publicKeyLocation());
    }
}
