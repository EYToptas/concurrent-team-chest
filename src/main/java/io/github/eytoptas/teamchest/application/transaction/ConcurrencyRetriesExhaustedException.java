package io.github.eytoptas.teamchest.application.transaction;

import io.github.eytoptas.teamchest.application.TeamChestException;

public class ConcurrencyRetriesExhaustedException extends TeamChestException {

    public ConcurrencyRetriesExhaustedException(int attempts, Throwable cause) {
        super("Request could not be completed after " + attempts + " attempts due to lock contention", cause);
    }
}
