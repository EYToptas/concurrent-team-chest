package io.github.eytoptas.teamchest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = {
        "team-chest.rate-limit.enabled=true",
        "team-chest.rate-limit.progress.capacity=2",
        "team-chest.rate-limit.progress.refill-per-second=0.001",
        "team-chest.rate-limit.claim.enabled=false",
        "team-chest.rate-limit.claim.capacity=1",
        "team-chest.rate-limit.claim.refill-per-second=0.001"})
class RateLimitApiTests extends ApiIntegrationTest {

    private Campaign campaign;
    private String user;

    @BeforeEach
    void openCampaignAndJoin() throws Exception {
        campaign = activeCampaign();
        user = freshUser();
        postJoin(user).andExpect(status().isOk());
    }

    @Test
    void exceedingTheLimitIs429WithRetryAfterAndChangesNothing() throws Exception {
        useUpProgressLimit(user);
        String key = newKey();

        MvcResult rejected = postProgress(user, key, progressBody(campaign.getId(), 5))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.operation").value("progress"))
                .andReturn();

        long retryAfter = Long.parseLong(rejected.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).as("seconds until the next token (refill 0.001/s)").isBetween(990L, 1000L);
        assertThat(idempotencyRecordCount(user, key)).as("rejected before the service ran").isZero();
        long teamId = members.findTeamIdByCampaignIdAndUserId(campaign.getId(), user).orElseThrow();
        assertThat(teamPoints(teamId)).isEqualTo(10);
    }

    @Test
    void limitsArePerUser() throws Exception {
        useUpProgressLimit(user);
        String otherUser = freshUser();
        postJoin(otherUser).andExpect(status().isOk());

        postProgress(otherUser, campaign.getId(), 5).andExpect(status().isOk());
    }

    @Test
    void limitsArePerEndpointType() throws Exception {
        useUpProgressLimit(user);

        getOverview(user).andExpect(status().isOk());
        postJoin(user).andExpect(status().isOk());
    }

    @Test
    void theLimitFollowsTheTokenNotRequestHeaders() throws Exception {
        useUpProgressLimit(user);

        mvc.perform(post("/progress")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header("Idempotency-Key", newKey())
                        .header("X-Forwarded-For", "203.0.113.7")
                        .header("X-Real-IP", "203.0.113.8")
                        .header("X-User-Id", freshUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progressBody(campaign.getId(), 5)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void anEndpointWithItsLimitSwitchedOffIsNotLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            postClaim(user, newKey(), campaign.getId())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHEST_NOT_READY"));
        }
    }

    private void useUpProgressLimit(String userId) throws Exception {
        postProgress(userId, campaign.getId(), 5).andExpect(status().isOk());
        postProgress(userId, campaign.getId(), 5).andExpect(status().isOk());
        postProgress(userId, campaign.getId(), 5).andExpect(status().isTooManyRequests());
    }

    private static String freshUser() {
        return "rl-" + UUID.randomUUID();
    }
}
