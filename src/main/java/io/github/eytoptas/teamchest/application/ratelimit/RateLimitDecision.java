package io.github.eytoptas.teamchest.application.ratelimit;

import java.time.Duration;

public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, Duration.ZERO);

    public RateLimitDecision {
        if (allowed != retryAfter.isZero()) {
            throw new IllegalArgumentException("Only a rejection has a retry delay");
        }
    }

    public static RateLimitDecision allow() {
        return ALLOWED;
    }

    public static RateLimitDecision reject(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }
}
