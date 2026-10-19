package io.github.eytoptas.teamchest.api.response;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import java.time.Instant;

public record ClaimResponse(long campaignId, long teamId, @JsonRawValue String reward, Instant claimedAt, long version) {

    public static ClaimResponse from(ClaimResult.Claimed claimed) {
        return new ClaimResponse(claimed.campaignId(), claimed.teamId(), claimed.reward(), claimed.claimedAt(),
                claimed.version());
    }
}
