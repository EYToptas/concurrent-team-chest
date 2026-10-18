package io.github.eytoptas.teamchest.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamAssignmentCursor;
import io.github.eytoptas.teamchest.domain.team.TeamMember;
import org.junit.jupiter.api.Test;

class EntityMappingTests extends PersistenceIntegrationTest {

    @Test
    void campaignRoundTripsAllColumns() {
        Campaign campaign = createCampaign();

        Campaign loaded = inTransaction(() -> campaigns.findById(campaign.getId()).orElseThrow());

        assertThat(loaded.getCode()).isEqualTo(campaign.getCode());
        assertThat(loaded.getTeamCapacity()).isEqualTo((short) 30);
        assertThat(loaded.getTargetPoints()).isEqualTo(300);
        assertThat(loaded.getReward()).isEqualTo(REWARD);
        assertThat(loaded.getStartsAt()).isEqualTo(CAMPAIGN_START);
        assertThat(loaded.getEndsAt()).isEqualTo(CAMPAIGN_END);
    }

    @Test
    void instantsAreStoredAsUtcWithMicrosecondPrecision() {
        Team team = createTeam(createCampaign());

        Team loaded = inTransaction(() -> teams.findById(team.getId()).orElseThrow());
        String stored = jdbc.queryForObject(
                "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM team WHERE id = ?",
                String.class, team.getId());

        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored).isEqualTo("2026-10-18 12:00:00.123456");
    }

    @Test
    void teamCopiesTheCampaignLimits() {
        Team team = createTeam(createCampaign());

        Team loaded = inTransaction(() -> teams.findById(team.getId()).orElseThrow());

        assertThat(loaded.getCapacity()).isEqualTo((short) 30);
        assertThat(loaded.getTargetPoints()).isEqualTo(300);
        assertThat(loaded.getMemberCount()).isZero();
        assertThat(loaded.getStateSeq()).isZero();
        assertThat(loaded.getCompletedAt()).isNull();
    }

    @Test
    void memberRoundTripsAllColumns() {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        addMember(team, "alice");

        TeamMember loaded = inTransaction(() -> members.findByCampaignIdAndUserId(campaign.getId(), "alice").orElseThrow());

        assertThat(loaded.getTeamId()).isEqualTo(team.getId());
        assertThat(loaded.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(loaded.getPoints()).isZero();
        assertThat(loaded.getPointsReachedSeq()).isEqualTo(1);
        assertThat(loaded.getJoinedAt()).isEqualTo(NOW);
        assertThat(loaded.getClaimedAt()).isNull();
    }

    @Test
    void cursorRoundTripsItsFillingTeam() {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);

        runInTransaction(() -> cursors.findByCampaignIdForUpdate(campaign.getId()).orElseThrow().startFilling(team));
        TeamAssignmentCursor loaded = inTransaction(() -> cursors.findByCampaignIdForUpdate(campaign.getId()).orElseThrow());

        assertThat(loaded.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(loaded.fillingTeamId()).contains(team.getId());
    }

    @Test
    void campaignsCanBeFilteredByEnd() {
        Campaign campaign = createCampaign();

        assertThat(campaigns.findByEndsAtAfter(CAMPAIGN_END.minusSeconds(1))).extracting(Campaign::getId).contains(campaign.getId());
        assertThat(campaigns.findByEndsAtAfter(CAMPAIGN_END)).extracting(Campaign::getId).doesNotContain(campaign.getId());
    }
}
