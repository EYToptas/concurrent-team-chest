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
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotencyRecord;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    private void storeOutcome(ProgressCommand command, String code, ProgressResult outcome) {
        IdempotentRequest request = command.idempotentRequest();
        runInTransaction(() -> idempotencyRecords.saveAndFlush(IdempotencyRecord.start(
                        request.campaignId(), request.userId(), IdempotentOperation.PROGRESS,
                        request.idempotencyKey(), request.requestHash(), Instant.now()))
                .complete(code, jsonMapper.writeValueAsString(outcome)));
    }
}
