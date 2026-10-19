package io.github.eytoptas.teamchest.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreaker.class);
    private static final long CLOSED = Long.MIN_VALUE;

    private final Clock clock;
    private final Duration cooldown;
    private final AtomicLong openUntil = new AtomicLong(CLOSED);

    public RedisCircuitBreaker(Clock clock, Duration cooldown) {
        this.clock = clock;
        this.cooldown = cooldown;
    }

    public <T> T execute(String operation, Supplier<T> redisCall, T fallback) {
        long skipUntil = openUntil.get();
        boolean probing = false;
        if (skipUntil != CLOSED) {
            long now = nowMillis();
            if (now < skipUntil || !openUntil.compareAndSet(skipUntil, now + cooldown.toMillis())) {
                return fallback;
            }
            probing = true;
        }
        try {
            T result = redisCall.get();
            if (probing) {
                openUntil.set(CLOSED);
                log.info("Redis is reachable again; cache and rate limiting are back on");
            }
            return result;
        } catch (RuntimeException e) {
            open(operation, e);
            return fallback;
        }
    }

    public boolean isOpen() {
        return openUntil.get() != CLOSED;
    }

    private void open(String operation, RuntimeException cause) {
        long previous = openUntil.getAndSet(nowMillis() + cooldown.toMillis());
        if (previous == CLOSED) {
            log.warn("Redis {} failed; serving without Redis (cache skipped, rate limits not enforced) for {}: {}",
                    operation, cooldown, cause.toString());
        } else {
            log.debug("Redis {} failed again while probing: {}", operation, cause.toString());
        }
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }
}
