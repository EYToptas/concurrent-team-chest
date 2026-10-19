package io.github.eytoptas.teamchest.api.response;

import io.github.eytoptas.teamchest.application.join.JoinResult;

public record JoinResponse(long campaignId, long teamId, boolean newlyJoined) {

    public static JoinResponse from(JoinResult result) {
        return new JoinResponse(result.campaignId(), result.teamId(), result.newlyJoined());
    }
}
