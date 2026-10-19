package io.github.eytoptas.teamchest.application.claim;

import io.github.eytoptas.teamchest.application.Commands;
import io.github.eytoptas.teamchest.application.idempotency.IdempotentRequest;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;

public record ClaimCommand(String userId, long campaignId, String idempotencyKey) {

    public ClaimCommand {
        Commands.requireUserId(userId);
        Commands.requireIdempotencyKey(idempotencyKey);
    }

    public IdempotentRequest idempotentRequest() {
        return new IdempotentRequest(campaignId, userId, IdempotentOperation.CLAIM, idempotencyKey,
                IdempotentRequest.fingerprint(IdempotentOperation.CLAIM, campaignId));
    }
}
