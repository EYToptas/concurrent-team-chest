package io.github.eytoptas.teamchest.domain.team;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "team")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "capacity", nullable = false, updatable = false)
    private short capacity;

    @Column(name = "target_points", nullable = false, updatable = false)
    private int targetPoints;

    @Column(name = "member_count", nullable = false)
    private short memberCount;

    @Column(name = "total_points", nullable = false)
    private int totalPoints;

    @Column(name = "state_seq", nullable = false)
    private long stateSeq;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Team(Long campaignId, short capacity, int targetPoints, Instant createdAt) {
        this.campaignId = campaignId;
        this.capacity = capacity;
        this.targetPoints = targetPoints;
        this.memberCount = 0;
        this.totalPoints = 0;
        this.stateSeq = 0;
        this.createdAt = createdAt;
    }

    public static Team createFor(Campaign campaign, Instant createdAt) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(createdAt, "createdAt");
        if (campaign.getId() == null) {
            throw new IllegalStateException("A team can only be created for a persisted campaign");
        }
        return new Team(campaign.getId(), campaign.getTeamCapacity(), campaign.getTargetPoints(), createdAt);
    }

    public boolean isFull() {
        return memberCount >= capacity;
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    public int remainingPoints() {
        return targetPoints - totalPoints;
    }

    public TeamMember addMember(String userId, Instant joinedAt) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User id must not be blank");
        }
        Objects.requireNonNull(joinedAt, "joinedAt");
        requirePersisted();
        if (isFull()) {
            throw new IllegalStateException("Team " + id + " is full");
        }
        memberCount++;
        stateSeq++;
        return new TeamMember(campaignId, id, userId, stateSeq, joinedAt);
    }

    public ProgressOutcome applyPoints(TeamMember member, int requestedPoints, Instant now) {
        requireOwnMember(member);
        if (requestedPoints <= 0) {
            throw new IllegalArgumentException("Requested points must be positive");
        }
        Objects.requireNonNull(now, "now");
        if (isCompleted()) {
            return new ProgressOutcome.TeamAlreadyCompleted(totalPoints, targetPoints);
        }

        int appliedPoints = Math.min(requestedPoints, remainingPoints());
        totalPoints += appliedPoints;
        stateSeq++;
        if (totalPoints == targetPoints) {
            completedAt = now;
        }
        member.recordPoints(appliedPoints, stateSeq);

        return new ProgressOutcome.Applied(
                requestedPoints, appliedPoints, member.getPoints(), totalPoints, targetPoints, isCompleted());
    }

    public ClaimOutcome claimReward(TeamMember member, Instant now) {
        requireOwnMember(member);
        Objects.requireNonNull(now, "now");
        if (!isCompleted()) {
            return new ClaimOutcome.ChestNotReady(totalPoints, targetPoints);
        }
        if (member.hasClaimed()) {
            return new ClaimOutcome.AlreadyClaimed(member.getClaimedAt());
        }

        member.markClaimed(now);
        stateSeq++;
        return new ClaimOutcome.Claimed(now);
    }

    private void requirePersisted() {
        if (id == null) {
            throw new IllegalStateException("Team must be persisted before members are added");
        }
    }

    private void requireOwnMember(TeamMember member) {
        Objects.requireNonNull(member, "member");
        if (!member.belongsTo(this)) {
            throw new IllegalArgumentException(
                    "Member " + member.getId() + " does not belong to team " + id);
        }
    }
}
