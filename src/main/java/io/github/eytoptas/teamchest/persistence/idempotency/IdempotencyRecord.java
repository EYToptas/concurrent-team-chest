package io.github.eytoptas.teamchest.persistence.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    private static final int MAX_KEY_LENGTH = 64;
    private static final int HASH_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "operation", nullable = false, updatable = false, length = 16)
    private IdempotentOperation operation;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = MAX_KEY_LENGTH)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, columnDefinition = "char")
    private String requestHash;

    @Column(name = "result_code", length = 32)
    private String resultCode;

    @Column(name = "result_payload", columnDefinition = "json")
    private String resultPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private IdempotencyRecord(Long campaignId, String userId, IdempotentOperation operation,
                              String idempotencyKey, String requestHash, Instant createdAt) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    public static IdempotencyRecord start(Long campaignId, String userId, IdempotentOperation operation,
                                          String idempotencyKey, String requestHash, Instant createdAt) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(createdAt, "createdAt");
        requireText(userId, "userId");
        requireText(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        if (requestHash == null || requestHash.length() != HASH_LENGTH) {
            throw new IllegalArgumentException("Request hash must be " + HASH_LENGTH + " hex characters");
        }
        return new IdempotencyRecord(campaignId, userId, operation, idempotencyKey, requestHash, createdAt);
    }

    public boolean isCompleted() {
        return resultCode != null;
    }

    public boolean isForRequest(String requestHash) {
        return this.requestHash.equals(requestHash);
    }

    public void complete(String resultCode, String resultPayload) {
        if (isCompleted()) {
            throw new IllegalStateException("Idempotency record " + id + " is already completed");
        }
        requireText(resultCode, "resultCode");
        requireText(resultPayload, "resultPayload");
        this.resultCode = resultCode;
        this.resultPayload = resultPayload;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
