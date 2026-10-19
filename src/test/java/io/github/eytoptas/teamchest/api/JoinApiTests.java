package io.github.eytoptas.teamchest.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class JoinApiTests extends ApiIntegrationTest {

    @Test
    void joiningReturnsTheTeamAndJoiningAgainReturnsTheSameTeam() throws Exception {
        Campaign campaign = activeCampaign();

        String firstResponse = postJoin("alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(campaign.getId()))
                .andExpect(jsonPath("$.newlyJoined").value(true))
                .andReturn().getResponse().getContentAsString();
        Number teamId = JsonPath.read(firstResponse, "$.teamId");

        postJoin("alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(teamId.longValue()))
                .andExpect(jsonPath("$.newlyJoined").value(false));
    }

    @Test
    void joiningWithoutAnActiveCampaignIs404() throws Exception {
        postJoin("alice")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CAMPAIGN"));
    }

    @Test
    void joiningAfterTheCampaignEndedIs404() throws Exception {
        endedCampaignInGracePeriod();

        postJoin("alice")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CAMPAIGN"));
    }

    @Test
    void wrongHttpMethodIs405() throws Exception {
        mvc.perform(get("/join").header(HttpHeaders.AUTHORIZATION, bearer("alice")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
