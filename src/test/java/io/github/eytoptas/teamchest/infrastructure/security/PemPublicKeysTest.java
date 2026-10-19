package io.github.eytoptas.teamchest.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

class PemPublicKeysTest {

    @TempDir
    Path directory;

    @Test
    void readsAnX509PemRsaPublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PublicKey publicKey = generator.generateKeyPair().getPublic();
        Path pem = directory.resolve("jwt-public.pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----\n");

        RSAPublicKey loaded = PemPublicKeys.read(new FileSystemResource(pem));

        assertThat(loaded.getEncoded()).isEqualTo(publicKey.getEncoded());
    }

    @Test
    void failsClearlyWhenTheFileIsMissing() {
        assertThatThrownBy(() -> PemPublicKeys.read(new FileSystemResource(directory.resolve("missing.pem"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.pem");
    }

    @Test
    void failsClearlyWhenTheFileIsNotAKey() throws Exception {
        Path garbage = Files.writeString(directory.resolve("garbage.pem"), "not a key");

        assertThatThrownBy(() -> PemPublicKeys.read(new FileSystemResource(garbage)))
                .isInstanceOf(IllegalStateException.class);
    }
}
