package io.github.eytoptas.teamchest.application;

public abstract class TeamChestException extends RuntimeException {

    protected TeamChestException(String message) {
        super(message);
    }

    protected TeamChestException(String message, Throwable cause) {
        super(message, cause);
    }
}
