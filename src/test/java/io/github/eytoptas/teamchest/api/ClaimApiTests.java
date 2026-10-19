package io.github.eytoptas.teamchest.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class ClaimApiTests extends ApiIntegrationTest {

    @Test
    void claimReturnsTheRewardAsJson() throws Exception {
        Campaign campaign = activeCampaign();
        completedTeamWith(campaign, "alice");

        postClaim("alice", newKey(), campaign.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(campaign.getId()))
                .andExpect(jsonPath("$.reward.coins").value(500))
                .andExpect(jsonPath("$.claimedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void claimBeforeTheTargetIs409ChestNotReady() throws Exception {
        Campaign campaign = activeCampaign();
        teamWith(campaign, "alice");

        postClaim("alice", newKey(), campaign.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHEST_NOT_READY"))
                .andExpect(jsonPath("$.details.teamPoints").value(0))
                .andExpect(jsonPath("$.details.targetPoints").value(300));
    }

    @Test
    void secondClaimIs409AlreadyClaimed() throws Exception {
        Campaign campaign = activeCampaign();
        completedTeamWith(campaign, "alice");
        postClaim("alice", newKey(), campaign.getId()).andExpect(status().isOk());

        postClaim("alice", newKey(), campaign.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CLAIMED"))
                .andExpect(jsonPath("$.details.claimedAt").isNotEmpty());
    }

    @Test
    void userWhoDidNotJoinIs409NotAMember() throws Exception {
        Campaign campaign = activeCampaign();

        postClaim("giovanni", newKey(), campaign.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));
    }

    @Test
    void claimDuringTheGracePeriodSucceeds() throws Exception {
        Campaign ended = endedCampaignInGracePeriod();
        completedTeamWith(ended, "alice");

        postClaim("alice", newKey(), ended.getId()).andExpect(status().isOk());
    }

    @Test
    void claimAfterTheGracePeriodIs409() throws Exception {
        Campaign closed = closedCampaign();
        completedTeamWith(closed, "alice");

        postClaim("alice", newKey(), closed.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_ACTIVE"));
    }

    @Test
    void claimRequiresAnIdempotencyKey() throws Exception {
        Campaign campaign = activeCampaign();

        mvc.perform(post("/claim")
                        .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\": " + campaign.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
