package io.github.eytoptas.teamchest.infrastructure.security;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;

final class PemPublicKeys {

    private PemPublicKeys() {
    }

    static RSAPublicKey read(Resource location) {
        try (InputStream in = location.getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read the JWT public key from " + location, e);
        }
    }
}
