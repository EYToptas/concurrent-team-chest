package io.github.eytoptas.teamchest.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetApiTests extends ApiIntegrationTest {

    @Test
    void beforeJoiningTheCampaignIsShownWithoutATeam() throws Exception {
        Campaign campaign = activeCampaign();

        getOverview("alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaign.campaignId").value(campaign.getId()))
                .andExpect(jsonPath("$.campaign.phase").value("ACTIVE"))
                .andExpect(jsonPath("$.campaign.teamCapacity").value(30))
                .andExpect(jsonPath("$.campaign.targetPoints").value(300))
                .andExpect(jsonPath("$.campaign.claimableUntil").isNotEmpty())
                .andExpect(jsonPath("$.team").value(nullValue()))
                .andExpect(jsonPath("$.me").value(nullValue()))
                .andExpect(jsonPath("$.canClaim").value(false));
    }

    @Test
    void membersAreRankedByPointsThenByWhoReachedThemFirst() throws Exception {
        Campaign campaign = activeCampaign();
        for (String user : List.of("erin", "frank", "dave", "gary")) {
            joinService.join(user);
        }
        progress(campaign, "frank", 5);
        progress(campaign, "dave", 5);
        progress(campaign, "erin", 7);

        getOverview("dave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.members[*].userId").value(contains("erin", "frank", "dave", "gary")))
                .andExpect(jsonPath("$.team.members[*].points").value(contains(7, 5, 5, 0)))
                .andExpect(jsonPath("$.team.members[*].rank").value(contains(1, 2, 3, 4)))
                .andExpect(jsonPath("$.team.memberCount").value(4))
                .andExpect(jsonPath("$.team.teamPoints").value(17))
                .andExpect(jsonPath("$.team.completed").value(false))
                .andExpect(jsonPath("$.me.userId").value("dave"))
                .andExpect(jsonPath("$.me.rank").value(3));
    }

    @Test
    void canClaimIsTrueOnlyForACompletedTeamUntilTheUserClaimed() throws Exception {
        Campaign campaign = activeCampaign();
        joinService.join("alice");
        progress(campaign, "alice", 300);

        getOverview("alice")
                .andExpect(jsonPath("$.team.completed").value(true))
                .andExpect(jsonPath("$.canClaim").value(true));

        claim(campaign, "alice");

        getOverview("alice")
                .andExpect(jsonPath("$.me.claimed").value(true))
                .andExpect(jsonPath("$.canClaim").value(false));
    }

    @Test
    void endedCampaignIsStillShownDuringTheGracePeriod() throws Exception {
        Campaign ended = endedCampaignInGracePeriod();
        completedTeamWith(ended, "alice");

        getOverview("alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaign.phase").value("CLAIM_ONLY"))
                .andExpect(jsonPath("$.canClaim").value(true));
    }

    @Test
    void noOpenCampaignIs404() throws Exception {
        closedCampaign();

        getOverview("alice")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CAMPAIGN"));
    }
}
