package io.github.eytoptas.teamchest.application;

public final class Commands {

    static final int MAX_USER_ID_LENGTH = 64;
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private Commands() {
    }

    public static void requireUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > MAX_USER_ID_LENGTH) {
            throw new IllegalArgumentException("userId must be 1-" + MAX_USER_ID_LENGTH + " characters");
        }
    }

    public static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }
}
