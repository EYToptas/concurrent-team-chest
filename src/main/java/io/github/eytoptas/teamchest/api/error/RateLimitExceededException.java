package io.github.eytoptas.teamchest.api.error;

import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import java.time.Duration;

public class RateLimitExceededException extends RuntimeException {

    private final RateLimitedOperation operation;
    private final Duration retryAfter;

    public RateLimitExceededException(RateLimitedOperation operation, Duration retryAfter) {
        super("Too many " + operation.key() + " requests, retry later");
        this.operation = operation;
        this.retryAfter = retryAfter;
    }

    public RateLimitedOperation operation() {
        return operation;
    }

    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
