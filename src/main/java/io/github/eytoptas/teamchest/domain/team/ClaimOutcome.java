package io.github.eytoptas.teamchest.domain.team;

import java.time.Instant;

public sealed interface ClaimOutcome {

    record Claimed(Instant claimedAt) implements ClaimOutcome {
    }

    record ChestNotReady(int teamPoints, int targetPoints) implements ClaimOutcome {
    }

    record AlreadyClaimed(Instant claimedAt) implements ClaimOutcome {
    }
}
