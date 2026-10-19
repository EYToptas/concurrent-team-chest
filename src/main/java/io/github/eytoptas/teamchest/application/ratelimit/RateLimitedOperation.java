package io.github.eytoptas.teamchest.application.ratelimit;

import java.util.Locale;

public enum RateLimitedOperation {

    JOIN,
    PROGRESS,
    CLAIM,
    GET;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
