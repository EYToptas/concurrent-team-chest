package io.github.eytoptas.teamchest.domain.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "campaign")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false, length = 64)
    private String code;

    @Column(name = "team_capacity", nullable = false, updatable = false)
    private short teamCapacity;

    @Column(name = "target_points", nullable = false, updatable = false)
    private int targetPoints;

    @Column(name = "reward", nullable = false, updatable = false, columnDefinition = "json")
    private String reward;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    public Campaign(String code, int teamCapacity, int targetPoints, String reward,
                    Instant startsAt, Instant endsAt) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Campaign code must not be blank");
        }
        if (teamCapacity <= 0 || teamCapacity > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Team capacity must be between 1 and " + Short.MAX_VALUE);
        }
        if (targetPoints <= 0) {
            throw new IllegalArgumentException("Target points must be positive");
        }
        Objects.requireNonNull(reward, "reward");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Campaign must end after it starts");
        }
        this.code = code;
        this.teamCapacity = (short) teamCapacity;
        this.targetPoints = targetPoints;
        this.reward = reward;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public CampaignPhase phaseAt(Instant now, Duration claimGracePeriod) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(claimGracePeriod, "claimGracePeriod");
        if (claimGracePeriod.isNegative()) {
            throw new IllegalArgumentException("Claim grace period must not be negative");
        }
        if (now.isBefore(startsAt)) {
            return CampaignPhase.NOT_STARTED;
        }
        if (now.isBefore(endsAt)) {
            return CampaignPhase.ACTIVE;
        }
        if (now.isBefore(claimWindowEnd(claimGracePeriod))) {
            return CampaignPhase.CLAIM_ONLY;
        }
        return CampaignPhase.CLOSED;
    }

    public Instant claimWindowEnd(Duration claimGracePeriod) {
        return endsAt.plus(claimGracePeriod);
    }
}
