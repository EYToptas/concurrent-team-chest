package io.github.eytoptas.teamchest.persistence.team;

import java.time.Instant;

public record MemberTeamState(Long teamId, Instant teamCompletedAt) {

    public boolean teamCompleted() {
        return teamCompletedAt != null;
    }
}
