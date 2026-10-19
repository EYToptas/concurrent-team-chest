package io.github.eytoptas.teamchest.application.idempotency;

import io.github.eytoptas.teamchest.application.TeamChestException;

public class IdempotencyKeyReuseException extends TeamChestException {

    public IdempotencyKeyReuseException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' was already used for a different request");
    }
}
