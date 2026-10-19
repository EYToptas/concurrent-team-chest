package io.github.eytoptas.teamchest.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.campaign.CampaignNotActiveException;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import io.github.eytoptas.teamchest.application.transaction.ConcurrencyRetriesExhaustedException;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;

class IdempotentExecutorTests extends ServiceIntegrationTest {

    @Autowired
    private IdempotentExecutor executor;

    private OutcomeCodec<ProgressResult> codec;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        codec = new OutcomeCodec<>(jsonMapper, Map.of("NOT_A_MEMBER", ProgressResult.NotAMember.class));
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
    void preconditionsAreNotEvaluatedForAReplay() {
        IdempotentRequest request = request(newKey());
        executor.execute(request, codec, () -> campaign, ignored -> new ProgressResult.NotAMember(campaign.getId()));

        ProgressResult replayed = executor.execute(request, codec,
                () -> {
                    throw new CampaignNotActiveException(campaign.getId(), io.github.eytoptas.teamchest.domain.campaign.CampaignPhase.CLOSED);
                },
                ignored -> {
                    throw new AssertionError("work must not run for a replay");
                });

        assertThat(replayed).isEqualTo(new ProgressResult.NotAMember(campaign.getId()));
    }

    private IdempotentRequest request(String key) {
        return new IdempotentRequest(campaign.getId(), "alice", IdempotentOperation.PROGRESS, key,
                IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, campaign.getId(), 6));
    }
}
