package io.github.eytoptas.teamchest.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.campaign.CampaignNotActiveException;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import io.github.eytoptas.teamchest.application.transaction.ConcurrencyRetriesExhaustedException;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import io.github.eytoptas.teamchest.domain.team.ProgressOutcome;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamMember;
import io.github.eytoptas.teamchest.persistence.RecordedSql;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;

class IdempotentExecutorTests extends ServiceIntegrationTest {

    private static final String RECORD_INSERT = "%insert into idempotency_record%";

    @Autowired
    private IdempotentExecutor executor;

    private OutcomeCodec<ProgressResult> codec;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        codec = new OutcomeCodec<>(jsonMapper, Map.of(
                "SUCCESS", ProgressResult.Applied.class,
                "NOT_A_MEMBER", ProgressResult.NotAMember.class));
        campaign = activeCampaign();
    }

    @Test
    void failureRollsBackTheRecordAndEveryBusinessChange() {
        IdempotentRequest request = request(newKey());

        assertThatThrownBy(() -> executor.execute(request, codec, () -> campaign, ignored -> {
            teams.save(Team.createFor(campaign, NOW));
            throw new IllegalStateException("simulated failure after a write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(idempotencyRecordCount("alice", request.idempotencyKey())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team WHERE campaign_id = ?", Integer.class, campaign.getId()))
                .isZero();
    }

    @Test
    void requestCanBeRetriedWithTheSameKeyAfterAFailure() {
        IdempotentRequest request = request(newKey());
        assertThatThrownBy(() -> executor.execute(request, codec, () -> campaign, ignored -> {
            throw new IllegalStateException("simulated failure");
        })).isInstanceOf(IllegalStateException.class);

        ProgressResult result = executor.execute(request, codec, () -> campaign,
                ignored -> new ProgressResult.NotAMember(campaign.getId()));

        assertThat(result).isEqualTo(new ProgressResult.NotAMember(campaign.getId()));
        assertThat(idempotencyRecordCount("alice", request.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void lockConflictIsRetriedInAFreshTransaction() {
        IdempotentRequest request = request(newKey());
        AtomicInteger attempts = new AtomicInteger();

        ProgressResult result = executor.execute(request, codec, () -> campaign, ignored -> {
            teams.save(Team.createFor(campaign, NOW));
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("simulated deadlock");
            }
            return new ProgressResult.NotAMember(campaign.getId());
        });

        assertThat(result).isEqualTo(new ProgressResult.NotAMember(campaign.getId()));
        assertThat(attempts).hasValue(2);
        assertThat(idempotencyRecordCount("alice", request.idempotencyKey())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team WHERE campaign_id = ?", Integer.class, campaign.getId()))
                .as("only the successful attempt's write is committed").isEqualTo(1);
    }

    @Test
    void exhaustedRetriesStoreNothing() {
        IdempotentRequest request = request(newKey());

        assertThatThrownBy(() -> executor.execute(request, codec, () -> campaign, ignored -> {
            throw new CannotAcquireLockException("simulated persistent contention");
        })).isInstanceOf(ConcurrencyRetriesExhaustedException.class);

        assertThat(idempotencyRecordCount("alice", request.idempotencyKey())).isZero();
    }

    @Test
    void rejectedPreconditionsReplayAStoredOutcome() {
        IdempotentRequest request = request(newKey());
        executor.execute(request, codec, () -> campaign, ignored -> new ProgressResult.NotAMember(campaign.getId()));

        ProgressResult replayed = executor.execute(request, codec,
                () -> {
                    throw new CampaignNotActiveException(campaign.getId(), CampaignPhase.CLOSED);
                },
                ignored -> {
                    throw new AssertionError("work must not run for a replay");
                });

        assertThat(replayed).isEqualTo(new ProgressResult.NotAMember(campaign.getId()));
    }

    @Test
    void rejectedPreconditionsWithoutAStoredOutcomeAreRethrown() {
        IdempotentRequest request = request(newKey());
        CampaignNotActiveException rejection = new CampaignNotActiveException(campaign.getId(), CampaignPhase.CLOSED);

        assertThatThrownBy(() -> executor.execute(request, codec,
                () -> {
                    throw rejection;
                },
                ignored -> {
                    throw new AssertionError("work must not run for a rejected request");
                }))
                .isSameAs(rejection);
        assertThat(idempotencyRecordCount("alice", request.idempotencyKey())).isZero();
    }

    @Test
    void freshRequestIsNotLookedUpAndARetryReadsTheStoredOutcomeAfterItsInsertFailed() {
        IdempotentRequest request = request(newKey());
        ProgressResult answer = new ProgressResult.NotAMember(campaign.getId());
        AtomicInteger runs = new AtomicInteger();

        List<String> fresh = statementsOf(() -> executor.execute(request, codec, () -> campaign, ignored -> {
            runs.incrementAndGet();
            return answer;
        }));
        List<String> retry = statementsOf(() -> assertThat(executor.execute(request, codec, () -> campaign, ignored -> {
            runs.incrementAndGet();
            return answer;
        })).isEqualTo(answer));

        assertThat(fresh).satisfiesExactly(
                insert -> assertThat(insert).startsWith("insert into idempotency_record"),
                outcome -> assertThat(outcome).startsWith("update idempotency_record"));
        assertThat(retry).satisfiesExactly(
                insert -> assertThat(insert).startsWith("insert into idempotency_record"),
                lookup -> assertThat(lookup).startsWith("select").contains("from idempotency_record"));
        assertThat(runs).as("the work ran for the fresh request only").hasValue(1);
        assertThat(idempotencyRecordCount("alice", request.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void sameKeyRequestsQueuedBehindAFailedOneApplyTheWorkOnce() throws Exception {
        Team team = teamWith(campaign, "alice");
        IdempotentRequest request = request(newKey());
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<ProgressResult> first = async(() -> executor.execute(request, codec, () -> campaign, ignored -> {
            addSixPoints(team, runs);
            firstApplied.countDown();
            await(releaseFirst);
            throw new IllegalStateException("simulated failure after the points were applied");
        }));
        await(firstApplied);
        List<Future<ProgressResult>> waiters = List.of(
                async(() -> executor.execute(request, codec, () -> campaign, ignored -> addSixPoints(team, runs))),
                async(() -> executor.execute(request, codec, () -> campaign, ignored -> addSixPoints(team, runs))));

        awaitBlockedStatements(RECORD_INSERT, 2);
        releaseFirst.countDown();

        assertThatThrownBy(() -> result(first))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class);
        ProgressResult answer = result(waiters.get(0));
        assertThat(result(waiters.get(1))).as("both waiters answer with the same outcome").isEqualTo(answer);
        assertThat(answer).isInstanceOfSatisfying(ProgressResult.Applied.class, applied -> {
            assertThat(applied.userPoints()).isEqualTo(6);
            assertThat(applied.teamPoints()).isEqualTo(6);
        });
        assertThat(runs).as("the failed first run plus exactly one waiter").hasValue(2);
        assertThat(teamPoints(team.getId())).as("only one run is committed").isEqualTo(6);
        assertThat(memberPointSum(team.getId())).isEqualTo(6);
        assertThat(idempotencyRecordCount("alice", request.idempotencyKey())).isEqualTo(1);
    }

    private ProgressResult addSixPoints(Team team, AtomicInteger runs) {
        runs.incrementAndGet();
        Team locked = teams.findByIdForUpdate(team.getId()).orElseThrow();
        TeamMember alice = members.findByCampaignIdAndUserId(campaign.getId(), "alice").orElseThrow();
        if (!(locked.applyPoints(alice, 6, NOW) instanceof ProgressOutcome.Applied applied)) {
            throw new AssertionError("6 points must fit into the team");
        }
        return new ProgressResult.Applied(campaign.getId(), locked.getId(), applied.requestedPoints(),
                applied.appliedPoints(), applied.memberPoints(), applied.teamPoints(), applied.targetPoints(),
                applied.teamCompleted(), locked.getStateSeq());
    }

    private static List<String> statementsOf(Runnable work) {
        return RecordedSql.during(work).stream().map(sql -> sql.toLowerCase(Locale.ROOT)).toList();
    }

    private IdempotentRequest request(String key) {
        return new IdempotentRequest(campaign.getId(), "alice", IdempotentOperation.PROGRESS, key,
                IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, campaign.getId(), 6));
    }
}
