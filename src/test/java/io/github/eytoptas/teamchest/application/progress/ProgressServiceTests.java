package io.github.eytoptas.teamchest.application.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.campaign.CampaignNotActiveException;
import io.github.eytoptas.teamchest.application.campaign.CampaignNotFoundException;
import io.github.eytoptas.teamchest.application.idempotency.IdempotencyKeyReuseException;
import io.github.eytoptas.teamchest.application.idempotency.IdempotentRequest;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamMember;
import io.github.eytoptas.teamchest.persistence.RecordedSql;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotencyRecord;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ProgressServiceTests extends ServiceIntegrationTest {

    @Test
    void sampleTeamExample() {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();
        joinService.join("bob");
        joinService.join("carol");
        progress(campaign, "alice", 100);
        progress(campaign, "bob", 100);
        progress(campaign, "carol", 90);

        ProgressResult alice = progress(campaign, "alice", 6);
        ProgressResult bob = progress(campaign, "bob", 5);
        ProgressResult carol = progress(campaign, "carol", 3);

        assertThat(alice).isInstanceOfSatisfying(ProgressResult.Applied.class, applied -> {
            assertThat(applied.appliedPoints()).isEqualTo(6);
            assertThat(applied.userPoints()).isEqualTo(106);
            assertThat(applied.teamPoints()).isEqualTo(296);
            assertThat(applied.teamCompleted()).isFalse();
        });
        assertThat(bob).isInstanceOfSatisfying(ProgressResult.Applied.class, applied -> {
            assertThat(applied.requestedPoints()).isEqualTo(5);
            assertThat(applied.appliedPoints()).isEqualTo(4);
            assertThat(applied.userPoints()).isEqualTo(104);
            assertThat(applied.teamPoints()).isEqualTo(300);
            assertThat(applied.teamCompleted()).isTrue();
        });
        assertThat(carol).isEqualTo(new ProgressResult.TeamAlreadyCompleted(campaign.getId(), teamId, 300));
        assertThat(teamPoints(teamId)).isEqualTo(300).isEqualTo(memberPointSum(teamId));
    }

    @Test
    void retryWithTheSameKeyReturnsTheStoredResultAndAppliesOnce() {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();
        ProgressCommand command = new ProgressCommand("alice", campaign.getId(), 6, newKey());

        ProgressResult first = progressService.progress(command);
        ProgressResult retry = progressService.progress(command);

        assertThat(retry).isEqualTo(first);
        assertThat(teamPoints(teamId)).isEqualTo(6);
        assertThat(idempotencyRecordCount("alice", command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyApplyOnce() throws Exception {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();
        ProgressCommand command = new ProgressCommand("alice", campaign.getId(), 6, newKey());

        List<Future<ProgressResult>> retries = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            retries.add(async(() -> progressService.progress(command)));
        }
        List<ProgressResult> results = new ArrayList<>();
        for (Future<ProgressResult> retry : retries) {
            results.add(result(retry));
        }

        assertThat(results).containsOnly(results.get(0));
        assertThat(teamPoints(teamId)).isEqualTo(6);
        assertThat(idempotencyRecordCount("alice", command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentContentIsRejected() {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();
        String key = newKey();
        progressService.progress(new ProgressCommand("alice", campaign.getId(), 6, key));

        assertThatThrownBy(() -> progressService.progress(new ProgressCommand("alice", campaign.getId(), 60, key)))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(teamPoints(teamId)).isEqualTo(6);
    }

    @Test
    void concurrentProgressOfAFullTeamStopsExactlyAtTheTarget() throws Exception {
        Campaign campaign = activeCampaign();
        long teamId = 0;
        for (int i = 1; i <= 30; i++) {
            teamId = joinService.join("player-" + i).teamId();
        }

        List<Future<ProgressResult>> requests = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (int i = 1; i <= 30; i++) {
                String userId = "player-" + i;
                requests.add(async(() -> progress(campaign, userId, 4)));
            }
        }
        List<ProgressResult> results = new ArrayList<>();
        for (Future<ProgressResult> request : requests) {
            results.add(result(request));
        }

        List<ProgressResult.Applied> applied = results.stream()
                .filter(ProgressResult.Applied.class::isInstance).map(ProgressResult.Applied.class::cast).toList();
        assertThat(applied.stream().mapToInt(ProgressResult.Applied::appliedPoints).sum()).isEqualTo(300);
        assertThat(applied).filteredOn(ProgressResult.Applied::teamCompleted).hasSize(1);
        assertThat(results).filteredOn(ProgressResult.TeamAlreadyCompleted.class::isInstance)
                .hasSize(results.size() - applied.size());
        assertThat(teamPoints(teamId)).isEqualTo(300).isEqualTo(memberPointSum(teamId));
        assertThat(applied).extracting(ProgressResult.Applied::version).doesNotHaveDuplicates();
    }

    @Test
    void appliedProgressLocksTheTeamInOneStatement() {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();

        List<String> statements = statementsOf(() -> progress(campaign, "alice", 6));

        assertThat(statements).hasSize(6);
        assertThat(statements.subList(0, 3)).satisfiesExactly(
                insert -> assertThat(insert).startsWith("insert into idempotency_record"),
                lock -> assertThat(lock).startsWith("select").contains("from team ").contains("from team_member")
                        .contains(" for update"),
                member -> assertThat(member).startsWith("select").contains("from team_member")
                        .doesNotContain(" for update"));
        assertThat(statements.subList(3, 6)).allSatisfy(update -> assertThat(update).startsWith("update"));
        assertThat(teamPoints(teamId)).isEqualTo(6).isEqualTo(memberPointSum(teamId));
    }

    @Test
    void retryIsAnsweredFromTheStoredOutcomeWithoutTouchingTheTeam() {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();
        ProgressCommand command = new ProgressCommand("alice", campaign.getId(), 6, newKey());
        ProgressResult first = progressService.progress(command);
        int announcements = afterCommit.forTeam(teamId).size();

        List<ProgressResult> retried = new ArrayList<>();
        List<String> statements = statementsOf(() -> retried.add(progressService.progress(command)));

        assertThat(retried).containsExactly(first);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).contains("idempotency_record"));
        assertThat(statements.get(0)).startsWith("insert into idempotency_record");
        assertThat(statements.get(statements.size() - 1)).startsWith("select").contains("from idempotency_record");
        assertThat(teamPoints(teamId)).isEqualTo(6).isEqualTo(memberPointSum(teamId));
        assertThat(idempotencyRecordCount("alice", command.idempotencyKey())).isEqualTo(1);
        assertThat(afterCommit.forTeam(teamId)).as("a replay invalidates nothing").hasSize(announcements);
    }

    @Test
    void progressOfACompletedTeamIsRejectedWithoutLoadingTheMember() {
        Campaign campaign = activeCampaign();
        Team team = completedTeamWith(campaign, "alice", "bob");

        List<ProgressResult> answers = new ArrayList<>();
        List<String> statements = statementsOf(() -> answers.add(progress(campaign, "bob", 5)));

        assertThat(answers).containsExactly(new ProgressResult.TeamAlreadyCompleted(campaign.getId(), team.getId(), 300));
        assertThat(statements).filteredOn(sql -> sql.startsWith("select") && !sql.contains(" for update"))
                .noneMatch(sql -> sql.contains("from team_member"));
        assertThat(teamPoints(team.getId())).isEqualTo(300).isEqualTo(memberPointSum(team.getId()));
    }

    @Test
    void completionWhileWaitingForTheLockIsDecidedUnderTheLock() throws Exception {
        Campaign campaign = activeCampaign();
        Team team = teamWith(campaign, "alice", "bob");
        CountDownLatch holdsLock = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        Future<Void> completer = inTransactionAsync(() -> {
            Team locked = teams.findByIdForUpdate(team.getId()).orElseThrow();
            holdsLock.countDown();
            await(complete);
            TeamMember alice = members.findByCampaignIdAndUserId(campaign.getId(), "alice").orElseThrow();
            locked.applyPoints(alice, 300, Instant.now());
            return null;
        });
        await(holdsLock);

        Future<ProgressResult> late = async(() -> progress(campaign, "bob", 5));
        try {
            awaitBlockedStatements("%from team %for update%", 1);
        } finally {
            complete.countDown();
        }
        result(completer);

        assertThat(result(late)).isEqualTo(new ProgressResult.TeamAlreadyCompleted(campaign.getId(), team.getId(), 300));
        assertThat(teamPoints(team.getId())).isEqualTo(300).isEqualTo(memberPointSum(team.getId()));
        assertThat(jdbc.queryForObject("SELECT points FROM team_member WHERE campaign_id = ? AND user_id = 'bob'",
                Integer.class, campaign.getId())).isZero();
    }

    @Test
    void rejectionsAreStoredAndReplayedForTheSameKey() {
        Campaign campaign = activeCampaign();
        ProgressCommand beforeJoining = new ProgressCommand("alice", campaign.getId(), 6, newKey());

        assertThat(progressService.progress(beforeJoining)).isEqualTo(new ProgressResult.NotAMember(campaign.getId()));
        joinService.join("alice");

        assertThat(progressService.progress(beforeJoining)).isEqualTo(new ProgressResult.NotAMember(campaign.getId()));
        assertThat(progress(campaign, "alice", 6)).isInstanceOf(ProgressResult.Applied.class);
    }

    @Test
    void requestForAClosedCampaignDoesNotTouchTheCurrentCampaign() {
        Campaign oldCampaign = closedCampaign();
        teamWith(oldCampaign, "alice");
        Campaign currentCampaign = activeCampaign();
        long currentTeamId = joinService.join("alice").teamId();
        ProgressCommand lateRequest = new ProgressCommand("alice", oldCampaign.getId(), 6, newKey());

        assertThatThrownBy(() -> progressService.progress(lateRequest)).isInstanceOf(CampaignNotActiveException.class);

        assertThat(teamPoints(currentTeamId)).isZero();
        assertThat(idempotencyRecordCount("alice", lateRequest.idempotencyKey())).isZero();
        assertThat(currentCampaign.getId()).isNotEqualTo(oldCampaign.getId());
    }

    @Test
    void progressIsRejectedAfterTheCampaignEnded() {
        Campaign campaign = endedCampaignInGracePeriod();
        Team team = teamWith(campaign, "alice");

        assertThatThrownBy(() -> progress(campaign, "alice", 6)).isInstanceOf(CampaignNotActiveException.class);
        assertThat(teamPoints(team.getId())).isZero();
    }

    @Test
    void unknownCampaignIsRejected() {
        assertThatThrownBy(() -> progressService.progress(new ProgressCommand("alice", 999_999L, 6, newKey())))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    @Test
    void storedResultIsReplayedEvenAfterTheCampaignEnded() {
        Campaign campaign = endedCampaignInGracePeriod();
        Team team = teamWith(campaign, "alice");
        ProgressCommand command = new ProgressCommand("alice", campaign.getId(), 6, newKey());
        ProgressResult answeredDuringTheCampaign = new ProgressResult.Applied(
                campaign.getId(), team.getId(), 6, 6, 6, 6, 300, false, 2);
        storeOutcome(command, "SUCCESS", answeredDuringTheCampaign);

        assertThat(progressService.progress(command)).isEqualTo(answeredDuringTheCampaign);
    }

    @Test
    void sameKeyWithDifferentContentIsRejectedEvenAfterTheCampaignEnded() {
        Campaign campaign = endedCampaignInGracePeriod();
        Team team = teamWith(campaign, "alice");
        ProgressCommand command = new ProgressCommand("alice", campaign.getId(), 6, newKey());
        storeOutcome(command, "SUCCESS", new ProgressResult.Applied(campaign.getId(), team.getId(), 6, 6, 6, 6, 300, false, 2));

        assertThatThrownBy(() -> progressService.progress(
                new ProgressCommand("alice", campaign.getId(), 60, command.idempotencyKey())))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(idempotencyRecordCount("alice", command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void appliedProgressIsAnnouncedAfterCommitAndRejectionsAreNot() {
        Campaign campaign = activeCampaign();
        long teamId = joinService.join("alice").teamId();

        ProgressResult.Applied applied = (ProgressResult.Applied) progress(campaign, "alice", 300);
        progress(campaign, "alice", 5);

        assertThat(afterCommit.forTeam(teamId)).extracting(observation -> observation.campaign().version())
                .containsExactly(1L, applied.version());
        assertThat(afterCommit.forTeam(teamId)).allSatisfy(observation ->
                assertThat(observation.committedVersion()).isEqualTo(observation.campaign().version()));
    }

    @Test
    void invalidCommandsAreRejectedBeforeAnyWork() {
        assertThatThrownBy(() -> new ProgressCommand("alice", 1L, 0, newKey())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProgressCommand("alice", 1L, -5, newKey())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProgressCommand(" ", 1L, 5, newKey())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProgressCommand("alice", 1L, 5, "k".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> statementsOf(Runnable work) {
        return RecordedSql.during(work).stream().map(sql -> sql.toLowerCase(Locale.ROOT)).toList();
    }

    private void storeOutcome(ProgressCommand command, String code, ProgressResult outcome) {
        IdempotentRequest request = command.idempotentRequest();
        runInTransaction(() -> idempotencyRecords.saveAndFlush(IdempotencyRecord.start(
                        request.campaignId(), request.userId(), IdempotentOperation.PROGRESS,
                        request.idempotencyKey(), request.requestHash(), Instant.now()))
                .complete(code, jsonMapper.writeValueAsString(outcome)));
    }
}
