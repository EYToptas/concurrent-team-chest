package io.github.eytoptas.teamchest.application.join;

import io.github.eytoptas.teamchest.application.TeamChestException;

public class JoinBusyException extends TeamChestException {

    public JoinBusyException(String message) {
        super(message);
    }
}
