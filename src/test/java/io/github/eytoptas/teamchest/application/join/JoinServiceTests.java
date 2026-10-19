package io.github.eytoptas.teamchest.application.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.cache.TeamStateChanged;
import io.github.eytoptas.teamchest.application.campaign.NoActiveCampaignException;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class JoinServiceTests extends ServiceIntegrationTest {

    @Test
    void firstJoinOpensATeamAndTheNextJoinsFillIt() {
        Campaign campaign = activeCampaign();

        JoinResult first = joinService.join("alice");
        JoinResult second = joinService.join("bob");

        assertThat(first.newlyJoined()).isTrue();
        assertThat(first.campaignId()).isEqualTo(campaign.getId());
        assertThat(second.teamId()).isEqualTo(first.teamId());
        assertThat(fillingTeamId(campaign)).isEqualTo(first.teamId());
        assertThat(teamSizes(campaign)).containsExactly(2);
    }

    @Test
    void joiningAgainReturnsTheExistingTeam() {
        activeCampaign();
        JoinResult first = joinService.join("alice");

        JoinResult again = joinService.join("alice");

        assertThat(again).isEqualTo(new JoinResult(first.campaignId(), first.teamId(), false));
    }

    @Test
    void thirtyFirstPlayerOpensTheNextTeam() {
        Campaign campaign = activeCampaign();
        IntStream.rangeClosed(1, 30).forEach(i -> joinService.join("player-" + i));
        assertThat(fillingTeamId(campaign)).as("a full team is released").isNull();

        JoinResult thirtyFirst = joinService.join("player-31");

        assertThat(teamSizes(campaign)).containsExactly(30, 1);
        assertThat(fillingTeamId(campaign)).isEqualTo(thirtyFirst.teamId());
    }

    @Test
    void concurrentJoinsFillTeamsOneAtATime() throws Exception {
        Campaign campaign = activeCampaign();

        List<Future<JoinResult>> joins = new ArrayList<>();
        for (int i = 1; i <= 146; i++) {
            String userId = "player-" + i;
            joins.add(async(() -> joinService.join(userId)));
        }
        for (Future<JoinResult> join : joins) {
            assertThat(result(join).newlyJoined()).isTrue();
        }

        assertThat(teamSizes(campaign)).containsExactly(30, 30, 30, 30, 26);
        assertThat(memberRowsPerTeam(campaign)).containsExactly(30, 30, 30, 30, 26);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM team_member WHERE campaign_id = ?",
                Integer.class, campaign.getId())).isEqualTo(146);
        assertThat(fillingTeamId(campaign)).isEqualTo(lastTeamId(campaign));
    }

    @Test
    void sameUserJoiningFromSeveralDevicesAtOnceGetsOneMembership() throws Exception {
        Campaign campaign = activeCampaign();

        List<Future<JoinResult>> devices = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            devices.add(async(() -> joinService.join("alice")));
        }
        List<JoinResult> results = new ArrayList<>();
        for (Future<JoinResult> device : devices) {
            results.add(result(device));
        }

        assertThat(results).extracting(JoinResult::teamId).containsOnly(results.get(0).teamId());
        assertThat(results).filteredOn(JoinResult::newlyJoined).hasSize(1);
        assertThat(teamSizes(campaign)).containsExactly(1);
    }

    @Test
    void joinIsRejectedWhenNoCampaignAcceptsJoins() {
        assertThatThrownBy(() -> joinService.join("alice")).isInstanceOf(NoActiveCampaignException.class);

        endedCampaignInGracePeriod();

        assertThatThrownBy(() -> joinService.join("alice")).isInstanceOf(NoActiveCampaignException.class);
    }

    @Test
    void successfulJoinIsAnnouncedAfterCommitOnly() {
        activeCampaign();

        JoinResult joined = joinService.join("alice");
        joinService.join("alice");

        assertThat(afterCommit.forTeam(joined.teamId())).singleElement().satisfies(observation -> {
            assertThat(observation.campaign()).isEqualTo(new TeamStateChanged(joined.campaignId(), joined.teamId(), 1));
            assertThat(observation.committedVersion()).isEqualTo(1);
        });
    }

    private List<Integer> teamSizes(Campaign campaign) {
        return jdbc.queryForList("SELECT member_count FROM team WHERE campaign_id = ? ORDER BY id",
                Integer.class, campaign.getId());
    }

    private List<Integer> memberRowsPerTeam(Campaign campaign) {
        return jdbc.queryForList("""
                SELECT COUNT(*) FROM team_member WHERE campaign_id = ? GROUP BY team_id ORDER BY team_id
                """, Integer.class, campaign.getId());
    }

    private Long fillingTeamId(Campaign campaign) {
        return jdbc.queryForObject("SELECT filling_team_id FROM team_assignment_cursor WHERE campaign_id = ?",
                Long.class, campaign.getId());
    }

    private Long lastTeamId(Campaign campaign) {
        return jdbc.queryForObject("SELECT MAX(id) FROM team WHERE campaign_id = ?", Long.class, campaign.getId());
    }
}
