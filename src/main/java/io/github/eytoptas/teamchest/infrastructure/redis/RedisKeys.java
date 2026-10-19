package io.github.eytoptas.teamchest.infrastructure.redis;

import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;

final class RedisKeys {

    private static final String PREFIX = "tc:v1:";

    private RedisKeys() {
    }

    static String team(long campaignId, long teamId) {
        return PREFIX + "team:" + campaignId + ":" + teamId;
    }

    static String membership(long campaignId, String userId) {
        return PREFIX + "member:" + campaignId + ":" + userId;
    }

    static String rateLimit(RateLimitedOperation operation, String userId) {
        return PREFIX + "rl:" + operation.key() + ":" + userId;
    }
}
