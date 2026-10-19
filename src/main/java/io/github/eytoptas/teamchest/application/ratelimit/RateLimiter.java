package io.github.eytoptas.teamchest.application.ratelimit;

public interface RateLimiter {

    RateLimitDecision tryAcquire(RateLimitedOperation operation, String userId);
}
