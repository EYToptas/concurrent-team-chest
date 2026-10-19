package io.github.eytoptas.teamchest.application.transaction;

import org.springframework.dao.PessimisticLockingFailureException;

public class UnexpectedConcurrentUpdateException extends PessimisticLockingFailureException {

    public UnexpectedConcurrentUpdateException(String message) {
        super(message);
    }
}
