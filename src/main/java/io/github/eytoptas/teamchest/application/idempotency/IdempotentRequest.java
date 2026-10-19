package io.github.eytoptas.teamchest.application.idempotency;

import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record IdempotentRequest(long campaignId, String userId, IdempotentOperation operation,
                                String idempotencyKey, String requestHash) {

    public IdempotentRequest {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
    }

    public static String fingerprint(IdempotentOperation operation, long campaignId, Object... parts) {
        StringBuilder canonical = new StringBuilder(operation.name()).append('|').append(campaignId);
        for (Object part : parts) {
            canonical.append('|').append(part);
        }
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
