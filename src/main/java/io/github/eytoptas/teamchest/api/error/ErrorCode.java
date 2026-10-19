package io.github.eytoptas.teamchest.api.error;

public enum ErrorCode {

    VALIDATION_FAILED(400),
    MALFORMED_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CAMPAIGN_NOT_FOUND(404),
    NO_ACTIVE_CAMPAIGN(404),
    METHOD_NOT_ALLOWED(405),
    CAMPAIGN_NOT_ACTIVE(409),
    NOT_A_MEMBER(409),
    TEAM_COMPLETED(409),
    CHEST_NOT_READY(409),
    ALREADY_CLAIMED(409),
    UNSUPPORTED_MEDIA_TYPE(415),
    IDEMPOTENCY_KEY_REUSED(422),
    INTERNAL_ERROR(500),
    SERVICE_BUSY(503);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
