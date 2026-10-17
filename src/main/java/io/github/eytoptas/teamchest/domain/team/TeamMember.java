package io.github.eytoptas.teamchest.domain.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "team_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "team_id", nullable = false, updatable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 64)
    private String userId;

    @Column(name = "points", nullable = false)
    private int points;

    @Column(name = "points_reached_seq", nullable = false)
    private long pointsReachedSeq;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "claimed_at", updatable = false)
    private Instant claimedAt;

    TeamMember(Long campaignId, Long teamId, String userId, long joinedSeq, Instant joinedAt) {
        this.campaignId = campaignId;
        this.teamId = teamId;
        this.userId = userId;
        this.points = 0;
        this.pointsReachedSeq = joinedSeq;
        this.joinedAt = joinedAt;
    }

    public boolean hasClaimed() {
        return claimedAt != null;
    }

    boolean belongsTo(Team team) {
        return teamId != null && teamId.equals(team.getId());
    }

    void recordPoints(int appliedPoints, long reachedSeq) {
        this.points += appliedPoints;
        this.pointsReachedSeq = reachedSeq;
    }

    void markClaimed(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }
}
