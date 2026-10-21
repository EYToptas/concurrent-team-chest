package io.github.eytoptas.teamchest.application.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.campaign.CampaignNotActiveException;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.persistence.RecordedSql;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    void claimStartsWithTheInsertAndARetryReadsTheStoredOutcome() {
        Campaign campaign = activeCampaign();
        Team team = completedTeamWith(campaign, "alice");
        assertThat(claim(campaign, "giovanni")).isEqualTo(new ClaimResult.NotAMember(campaign.getId()));
        ClaimCommand command = new ClaimCommand("alice", campaign.getId(), newKey());
        List<ClaimResult> answers = new ArrayList<>();

        List<String> fresh = statementsOf(() -> answers.add(claimService.claim(command)));
        List<String> retry = statementsOf(() -> answers.add(claimService.claim(command)));

        assertThat(answers).hasSize(2).containsOnly(answers.get(0)).first().isInstanceOf(ClaimResult.Claimed.class);
        assertThat(fresh.get(0)).startsWith("insert into idempotency_record");
        assertThat(fresh.subList(1, fresh.size()))
                .noneMatch(sql -> sql.contains("from idempotency_record") || sql.startsWith("insert"));
        assertThat(retry).satisfiesExactly(
                insert -> assertThat(insert).startsWith("insert into idempotency_record"),
                lookup -> assertThat(lookup).startsWith("select").contains("from idempotency_record"));
        assertThat(claimedMembers(team.getId())).isEqualTo(1);
        assertThat(idempotencyRecordCount("alice", command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void claimLocksTheTeamInOneStatement() {
        Campaign campaign = activeCampaign();
        Team team = completedTeamWith(campaign, "alice");
        assertThat(claim(campaign, "giovanni")).isEqualTo(new ClaimResult.NotAMember(campaign.getId()));
        List<ClaimResult> answers = new ArrayList<>();

        List<String> statements = statementsOf(() -> answers.add(claim(campaign, "alice")));

        assertThat(answers).singleElement().isInstanceOf(ClaimResult.Claimed.class);
        assertThat(statements.subList(0, 3)).satisfiesExactly(
                insert -> assertThat(insert).startsWith("insert into idempotency_record"),
                lock -> assertThat(lock).startsWith("select").contains("from team ").contains("from team_member")
                        .contains(" for update"),
                member -> assertThat(member).startsWith("select").contains("from team_member")
                        .doesNotContain(" for update"));
        assertThat(statements.subList(3, statements.size())).allSatisfy(sql -> assertThat(sql).startsWith("update"));
        assertThat(claimedMembers(team.getId())).isEqualTo(1);
    }

    @Test
    void notReadyChestIsAnsweredWithoutLoadingTheMember() {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();
        progress(campaign, "alice", 120);
        List<ClaimResult> answers = new ArrayList<>();

        List<String> statements = statementsOf(() -> answers.add(claim(campaign, "alice")));

        assertThat(answers).containsExactly(new ClaimResult.ChestNotReady(campaign.getId(), teamId, 120, 300));
        assertThat(statements).filteredOn(sql -> sql.startsWith("select") && !sql.contains(" for update"))
                .noneMatch(sql -> sql.contains("from team_member"));
        assertThat(claimedMembers(teamId)).isZero();
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

    private static List<String> statementsOf(Runnable work) {
        return RecordedSql.during(work).stream().map(sql -> sql.toLowerCase(Locale.ROOT)).toList();
    }

    private int claimedMembers(long teamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE team_id = ? AND claimed_at IS NOT NULL",
                Integer.class, teamId);
    }

    private long stateSeq(long teamId) {
        return jdbc.queryForObject("SELECT state_seq FROM team WHERE id = ?", Long.class, teamId);
    }
}
