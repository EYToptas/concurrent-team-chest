package io.github.eytoptas.teamchest.domain.team;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "team_assignment_cursor")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamAssignmentCursor {

    @Id
    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "filling_team_id")
    private Long fillingTeamId;

    private TeamAssignmentCursor(Long campaignId) {
        this.campaignId = campaignId;
    }

    public static TeamAssignmentCursor forCampaign(Campaign campaign) {
        Objects.requireNonNull(campaign, "campaign");
        if (campaign.getId() == null) {
            throw new IllegalStateException("A cursor can only be created for a persisted campaign");
        }
        return new TeamAssignmentCursor(campaign.getId());
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public Optional<Long> fillingTeamId() {
        return Optional.ofNullable(fillingTeamId);
    }

    public void startFilling(Team team) {
        requireSameCampaign(team);
        if (fillingTeamId != null) {
            throw new IllegalStateException(
                    "Team " + fillingTeamId + " is still filling; a second filling team is not allowed");
        }
        if (team.getId() == null) {
            throw new IllegalStateException("Filling team must be persisted");
        }
        if (team.isFull()) {
            throw new IllegalStateException("A full team cannot become the filling team");
        }
        fillingTeamId = team.getId();
    }

    public void afterMemberJoined(Team team) {
        requireSameCampaign(team);
        if (!Objects.equals(fillingTeamId, team.getId())) {
            throw new IllegalStateException(
                    "Team " + team.getId() + " is not the filling team of campaign " + campaignId);
        }
        if (team.isFull()) {
            fillingTeamId = null;
        }
    }

    private void requireSameCampaign(Team team) {
        Objects.requireNonNull(team, "team");
        if (!campaignId.equals(team.getCampaignId())) {
            throw new IllegalArgumentException(
                    "Team " + team.getId() + " belongs to campaign " + team.getCampaignId() + ", not " + campaignId);
        }
    }
}
