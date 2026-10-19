package io.github.eytoptas.teamchest.application.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.campaign.CampaignNotActiveException;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ClaimServiceTests extends ServiceIntegrationTest {

    @Test
    void claimExample() {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();
        joinService.join("bob");
        joinService.join("carol");
        progress(campaign, "alice", 290);

        assertThat(claim(campaign, "carol")).isEqualTo(new ClaimResult.ChestNotReady(campaign.getId(), teamId, 290, 300));
        progress(campaign, "alice", 6);
        progress(campaign, "bob", 5);

        assertThat(claim(campaign, "carol")).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(claim(campaign, "alice")).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(claim(campaign, "bob")).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(claim(campaign, "alice")).isInstanceOf(ClaimResult.AlreadyClaimed.class);
        assertThat(claimedMembers(teamId)).isEqualTo(3);
    }

    @Test
    void claimReturnsTheCampaignRewardAndTheNewVersion() {
        Campaign campaign = activeCampaign();
        Team team = completedTeamWith(campaign, "alice");

        ClaimResult result = claim(campaign, "alice");

        assertThat(result).isInstanceOfSatisfying(ClaimResult.Claimed.class, claimed -> {
            assertThat(claimed.reward()).isEqualTo(REWARD);
            assertThat(claimed.teamId()).isEqualTo(team.getId());
            assertThat(claimed.version()).isEqualTo(stateSeq(team.getId()));
        });
    }

    @Test
    void severalDevicesClaimingAtOnceGetTheRewardOnce() throws Exception {
        Campaign campaign = activeCampaign();
        Team team = completedTeamWith(campaign, "alice");

        List<Future<ClaimResult>> devices = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            devices.add(async(() -> claim(campaign, "alice")));
        }
        List<ClaimResult> results = new ArrayList<>();
        for (Future<ClaimResult> device : devices) {
            results.add(result(device));
        }

        assertThat(results).filteredOn(ClaimResult.Claimed.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ClaimResult.AlreadyClaimed.class::isInstance).hasSize(7);
        ClaimResult.Claimed claimed = (ClaimResult.Claimed) results.stream()
                .filter(ClaimResult.Claimed.class::isInstance).findFirst().orElseThrow();
        assertThat(results).filteredOn(ClaimResult.AlreadyClaimed.class::isInstance)
                .allSatisfy(rejected -> assertThat(((ClaimResult.AlreadyClaimed) rejected).claimedAt())
                        .isEqualTo(claimed.claimedAt()));
        assertThat(claimedMembers(team.getId())).isEqualTo(1);
    }

    @Test
    void concurrentRetriesWithTheSameKeyReturnTheSameClaim() throws Exception {
        Campaign campaign = activeCampaign();
        Team team = completedTeamWith(campaign, "alice");
        ClaimCommand command = new ClaimCommand("alice", campaign.getId(), newKey());

        List<Future<ClaimResult>> retries = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            retries.add(async(() -> claimService.claim(command)));
        }
        List<ClaimResult> results = new ArrayList<>();
        for (Future<ClaimResult> retry : retries) {
            results.add(result(retry));
        }

        assertThat(results).containsOnly(results.get(0)).first().isInstanceOf(ClaimResult.Claimed.class);
        assertThat(claimedMembers(team.getId())).isEqualTo(1);
        assertThat(idempotencyRecordCount("alice", command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void completedChestCanStillBeClaimedDuringTheGracePeriod() {
        Campaign campaign = endedCampaignInGracePeriod();
        completedTeamWith(campaign, "alice");

        assertThat(claim(campaign, "alice")).isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    void claimIsRejectedAfterTheGracePeriod() {
        Campaign campaign = closedCampaign();
        Team team = completedTeamWith(campaign, "alice");

        assertThatThrownBy(() -> claim(campaign, "alice")).isInstanceOf(CampaignNotActiveException.class);
        assertThat(claimedMembers(team.getId())).isZero();
    }

    @Test
    void claimAffectsOnlyTheCampaignItNames() {
        Campaign previous = endedCampaignInGracePeriod();
        Team previousTeam = completedTeamWith(previous, "alice");
        Campaign current = activeCampaign();
        Team currentTeam = completedTeamWith(current, "alice");

        assertThat(claim(previous, "alice")).isInstanceOf(ClaimResult.Claimed.class);

        assertThat(claimedMembers(previousTeam.getId())).isEqualTo(1);
        assertThat(claimedMembers(currentTeam.getId())).isZero();
    }

    @Test
    void userWhoDidNotJoinCannotClaim() {
        Campaign campaign = activeCampaign();
        completedTeamWith(campaign, "alice");

        assertThat(claim(campaign, "giovanni")).isEqualTo(new ClaimResult.NotAMember(campaign.getId()));
    }

    @Test
    void successfulClaimIsAnnouncedAfterCommitAndRejectionsAreNot() {
        Campaign campaign = activeCampaign();
        Team team = completedTeamWith(campaign, "alice");

        ClaimResult.Claimed claimed = (ClaimResult.Claimed) claim(campaign, "alice");
        claim(campaign, "alice");

        assertThat(afterCommit.forTeam(team.getId())).singleElement().satisfies(observation -> {
            assertThat(observation.campaign().version()).isEqualTo(claimed.version());
            assertThat(observation.committedVersion()).isEqualTo(claimed.version());
        });
    }

    private int claimedMembers(long teamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE team_id = ? AND claimed_at IS NOT NULL",
                Integer.class, teamId);
    }

    private long stateSeq(long teamId) {
        return jdbc.queryForObject("SELECT state_seq FROM team WHERE id = ?", Long.class, teamId);
    }
}
