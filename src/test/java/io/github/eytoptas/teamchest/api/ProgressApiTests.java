package io.github.eytoptas.teamchest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class ProgressApiTests extends ApiIntegrationTest {

    @Test
    void appliedProgressReturnsTheNewTotals() throws Exception {
        Campaign campaign = activeCampaign();
        postJoin("alice");

        postProgress("alice", campaign.getId(), 6)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(campaign.getId()))
                .andExpect(jsonPath("$.requestedPoints").value(6))
                .andExpect(jsonPath("$.appliedPoints").value(6))
                .andExpect(jsonPath("$.userPoints").value(6))
                .andExpect(jsonPath("$.teamPoints").value(6))
                .andExpect(jsonPath("$.targetPoints").value(300))
                .andExpect(jsonPath("$.teamCompleted").value(false))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void sampleTeamExampleOverHttp() throws Exception {
        Campaign campaign = activeCampaign();
        postJoin("alice");
        postJoin("bob");
        postJoin("carol");
        postProgress("alice", campaign.getId(), 100);
        postProgress("bob", campaign.getId(), 100);
        postProgress("carol", campaign.getId(), 90);

        postProgress("alice", campaign.getId(), 6)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamPoints").value(296));
        postProgress("bob", campaign.getId(), 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedPoints").value(5))
                .andExpect(jsonPath("$.appliedPoints").value(4))
                .andExpect(jsonPath("$.teamCompleted").value(true));
        postProgress("carol", campaign.getId(), 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_COMPLETED"))
                .andExpect(jsonPath("$.details.targetPoints").value(300));
    }

    @Test
    void retryWithTheSameKeyReturnsTheIdenticalResponse() throws Exception {
        Campaign campaign = activeCampaign();
        postJoin("alice");
        String key = newKey();

        String first = postProgress("alice", key, progressBody(campaign.getId(), 6))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String retry = postProgress("alice", key, progressBody(campaign.getId(), 6))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT total_points FROM team WHERE campaign_id = ?", Integer.class,
                campaign.getId())).isEqualTo(6);
    }

    @Test
    void storedRejectionIsReplayedIdentically() throws Exception {
        Campaign campaign = activeCampaign();
        String key = newKey();

        String first = postProgress("alice", key, progressBody(campaign.getId(), 6))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_A_MEMBER"))
                .andReturn().getResponse().getContentAsString();
        postJoin("alice");
        String retry = postProgress("alice", key, progressBody(campaign.getId(), 6))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
    }

    @Test
    void sameKeyWithDifferentContentIs422() throws Exception {
        Campaign campaign = activeCampaign();
        postJoin("alice");
        String key = newKey();
        postProgress("alice", key, progressBody(campaign.getId(), 6)).andExpect(status().isOk());

        postProgress("alice", key, progressBody(campaign.getId(), 60))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        Campaign campaign = activeCampaign();

        mvc.perform(post("/progress")
                        .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progressBody(campaign.getId(), 6)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedIdempotencyKeyIs400() throws Exception {
        Campaign campaign = activeCampaign();

        postProgress("alice", "not a valid key!", progressBody(campaign.getId(), 6))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"points\": 6}",
            "{\"campaignId\": 1}",
            "{\"campaignId\": 1, \"points\": 0}",
            "{\"campaignId\": 1, \"points\": -3}",
            "{\"campaignId\": -1, \"points\": 3}"})
    void invalidBodyIs400(String body) throws Exception {
        postProgress("alice", newKey(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.errors").isNotEmpty());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        postProgress("alice", newKey(), "{\"campaignId\": 1, \"points\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void nonJsonContentIs415() throws Exception {
        mvc.perform(post("/progress")
                        .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("points=6"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unknownCampaignIs404() throws Exception {
        postProgress("alice", 999_999L, 6)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_FOUND"));
    }

    @Test
    void progressForAnEndedCampaignIs409() throws Exception {
        Campaign ended = endedCampaignInGracePeriod();
        teamWith(ended, "alice");

        postProgress("alice", ended.getId(), 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_ACTIVE"))
                .andExpect(jsonPath("$.details.phase").value("CLAIM_ONLY"));
    }
}
