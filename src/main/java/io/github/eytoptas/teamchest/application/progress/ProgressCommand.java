package io.github.eytoptas.teamchest.application.progress;

import io.github.eytoptas.teamchest.application.Commands;
import io.github.eytoptas.teamchest.application.idempotency.IdempotentRequest;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;

public record ProgressCommand(String userId, long campaignId, int points, String idempotencyKey) {

    public ProgressCommand {
        Commands.requireUserId(userId);
        Commands.requireIdempotencyKey(idempotencyKey);
        if (points <= 0) {
            throw new IllegalArgumentException("points must be positive");
        }
    }

    public IdempotentRequest idempotentRequest() {
        return new IdempotentRequest(campaignId, userId, IdempotentOperation.PROGRESS, idempotencyKey,
                IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, campaignId, points));
    }
}
