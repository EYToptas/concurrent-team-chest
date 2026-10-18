package io.github.eytoptas.teamchest.persistence.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

class IdempotencyRecordRepositoryTests extends PersistenceIntegrationTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String RECORD_INSERT = "%insert into idempotency_record%";

    @Test
    void completedRecordIsFoundByUserOperationAndKey() {
        Campaign campaign = createCampaign();

        runInTransaction(() -> {
            IdempotencyRecord record = idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"));
            record.complete("SUCCESS", "{\"appliedPoints\": 6}");
        });

        IdempotencyRecord stored = idempotencyRecords
                .findByUserIdAndOperationAndIdempotencyKey("alice", IdempotentOperation.PROGRESS, "key-1")
                .orElseThrow();
        assertThat(stored.isCompleted()).isTrue();
        assertThat(stored.getResultCode()).isEqualTo("SUCCESS");
        assertThat(stored.getResultPayload()).isEqualTo("{\"appliedPoints\": 6}");
        assertThat(stored.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.isForRequest(HASH_A)).isTrue();
        assertThat(stored.isForRequest(HASH_B)).isFalse();
    }

    @Test
    void keysAreScopedPerUserAndOperation() {
        Campaign campaign = createCampaign();

        runInTransaction(() -> {
            idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"));
            idempotencyRecords.saveAndFlush(progressRecord(campaign, "bob", "key-1"));
            idempotencyRecords.saveAndFlush(IdempotencyRecord.start(
                    campaign.getId(), "alice", IdempotentOperation.CLAIM, "key-1", HASH_A, NOW));
        });

        assertThat(idempotencyRecords.findByUserIdAndOperationAndIdempotencyKey(
                "alice", IdempotentOperation.CLAIM, "key-1")).isPresent();
        assertThat(idempotencyRecords.findByUserIdAndOperationAndIdempotencyKey(
                "Alice", IdempotentOperation.PROGRESS, "key-1")).isEmpty();
    }

    @Test
    void duplicateKeyIsRejected() {
        Campaign campaign = createCampaign();
        runInTransaction(() -> idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1")));

        assertThatThrownBy(() -> runInTransaction(() ->
                idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentDuplicateWaitsAndFailsOnceTheFirstCommits() throws Exception {
        Campaign campaign = createCampaign();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<Void> first = inTransactionAsync(() -> {
            IdempotencyRecord record = idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"));
            firstInserted.countDown();
            await(releaseFirst);
            record.complete("SUCCESS", "{\"appliedPoints\": 6}");
            return null;
        });
        await(firstInserted);
        Future<IdempotencyRecord> duplicate = inTransactionAsync(() ->
                idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1")));

        awaitBlockedStatements(RECORD_INSERT, 1);
        assertThat(duplicate).isNotDone();
        releaseFirst.countDown();
        result(first);

        assertThatThrownBy(() -> result(duplicate))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(idempotencyRecords.findByUserIdAndOperationAndIdempotencyKey(
                "alice", IdempotentOperation.PROGRESS, "key-1"))
                .hasValueSatisfying(record -> assertThat(record.getResultCode()).isEqualTo("SUCCESS"));
    }

    @Test
    void concurrentDuplicateProceedsWhenTheFirstRollsBack() throws Exception {
        Campaign campaign = createCampaign();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<Void> first = inRolledBackTransactionAsync(() -> {
            idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"));
            firstInserted.countDown();
            await(releaseFirst);
            return null;
        });
        await(firstInserted);
        Future<String> duplicate = inTransactionAsync(() -> {
            IdempotencyRecord record = idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"));
            record.complete("TEAM_COMPLETED", "{}");
            return record.getResultCode();
        });

        awaitBlockedStatements(RECORD_INSERT, 1);
        releaseFirst.countDown();
        result(first);

        assertThat(result(duplicate)).isEqualTo("TEAM_COMPLETED");
    }

    @Test
    void twoWaitersOnARolledBackKeyDeadlockAndOneIsReportedAsLockingFailure() throws Exception {
        Campaign campaign = createCampaign();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<Void> first = inRolledBackTransactionAsync(() -> {
            idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"));
            firstInserted.countDown();
            await(releaseFirst);
            return null;
        });
        await(firstInserted);
        List<Future<IdempotencyRecord>> waiters = List.of(
                inTransactionAsync(() -> idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"))),
                inTransactionAsync(() -> idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", "key-1"))));

        awaitBlockedStatements(RECORD_INSERT, 2);
        releaseFirst.countDown();
        result(first);

        List<Throwable> failures = new ArrayList<>();
        int successes = 0;
        for (Future<IdempotencyRecord> waiter : waiters) {
            try {
                result(waiter);
                successes++;
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(failures).singleElement().isInstanceOf(PessimisticLockingFailureException.class);
    }

    @Test
    void purgeDeletesOnlyTheGivenCampaignInBatches() {
        Campaign closedCampaign = createCampaign();
        Campaign otherCampaign = createCampaign();
        runInTransaction(() -> {
            for (int i = 0; i < 5; i++) {
                idempotencyRecords.saveAndFlush(progressRecord(closedCampaign, "user-" + i, "key"));
            }
            idempotencyRecords.saveAndFlush(progressRecord(otherCampaign, "user-0", "other-key"));
        });

        List<Integer> batches = new ArrayList<>();
        int deleted;
        do {
            deleted = inTransaction(() -> idempotencyRecords.deleteBatchByCampaignId(closedCampaign.getId(), 2));
            batches.add(deleted);
        } while (deleted > 0);

        assertThat(batches).containsExactly(2, 2, 1, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE campaign_id = ?",
                Integer.class, otherCampaign.getId())).isEqualTo(1);
    }

    private IdempotencyRecord progressRecord(Campaign campaign, String userId, String key) {
        return IdempotencyRecord.start(campaign.getId(), userId, IdempotentOperation.PROGRESS, key, HASH_A, NOW);
    }
}
