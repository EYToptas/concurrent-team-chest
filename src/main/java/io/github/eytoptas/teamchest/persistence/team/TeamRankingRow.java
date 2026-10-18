package io.github.eytoptas.teamchest.persistence.team;

import java.time.Instant;

public record TeamRankingRow(
        Long teamId,
        Long campaignId,
        short memberCount,
        int totalPoints,
        int targetPoints,
        Instant completedAt,
        long stateSeq,
        String userId,
        int points,
        long pointsReachedSeq,
        Instant claimedAt) {
}
