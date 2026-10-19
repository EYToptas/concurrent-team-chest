package io.github.eytoptas.teamchest.application.claim;

import java.time.Instant;

public sealed interface ClaimResult {

    record Claimed(long campaignId, long teamId, String reward, Instant claimedAt, long version) implements ClaimResult {
    }

    record ChestNotReady(long campaignId, long teamId, int teamPoints, int targetPoints) implements ClaimResult {
    }

    record AlreadyClaimed(long campaignId, long teamId, Instant claimedAt) implements ClaimResult {
    }

    record NotAMember(long campaignId) implements ClaimResult {
    }
}
