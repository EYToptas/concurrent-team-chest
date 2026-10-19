package io.github.eytoptas.teamchest.persistence.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

class IdempotencyRecordRepositoryTests extends PersistenceIntegrationTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final String RECORD_INSERT = "%insert into idempotency_record%";
    private static final int DEADLOCK = 1213;
    private static final int DUPLICATE_KEY = 1062;

    private final String key = "key-" + UUID.randomUUID();
    private final String otherKey = "key-" + UUID.randomUUID();

    @Test
    void completedRecordIsFoundByUserOperationAndKey() {
        Campaign campaign = createCampaign();

        runInTransaction(() -> {
            IdempotencyRecord record = idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key));
            record.complete("SUCCESS", "{\"appliedPoints\": 6}");
        });

        IdempotencyRecord stored = storedRecord(key);
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
            idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key));
            idempotencyRecords.saveAndFlush(progressRecord(campaign, "bob", key));
            idempotencyRecords.saveAndFlush(IdempotencyRecord.start(
                    campaign.getId(), "alice", IdempotentOperation.CLAIM, key, HASH_A, NOW));
        });

        assertThat(idempotencyRecords.findByUserIdAndOperationAndIdempotencyKey(
                "alice", IdempotentOperation.CLAIM, key)).isPresent();
        assertThat(idempotencyRecords.findByUserIdAndOperationAndIdempotencyKey(
                "Alice", IdempotentOperation.PROGRESS, key)).isEmpty();
    }

    @Test
    void duplicateKeyIsRejected() {
        Campaign campaign = createCampaign();
        runInTransaction(() -> idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key)));

        assertThatThrownBy(() -> runInTransaction(() ->
                idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentDuplicateWaitsAndFailsOnceTheFirstCommits() throws Exception {
        Campaign campaign = createCampaign();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<Void> first = inTransactionAsync(() -> {
            IdempotencyRecord record = idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key));
            firstInserted.countDown();
            await(releaseFirst);
            record.complete("SUCCESS", "{\"appliedPoints\": 6}");
            return null;
        });
        await(firstInserted);
        Future<IdempotencyRecord> duplicate = inTransactionAsync(() ->
                idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key)));

        awaitBlockedStatements(RECORD_INSERT, 1);
        assertThat(duplicate).isNotDone();
        releaseFirst.countDown();
        result(first);

        assertThatThrownBy(() -> result(duplicate))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storedRecord(key).getResultCode()).isEqualTo("SUCCESS");
    }

    @Test
    void concurrentDuplicateProceedsWhenTheFirstRollsBack() throws Exception {
        Campaign campaign = createCampaign();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<Void> first = inRolledBackTransactionAsync(() -> {
            idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key));
            firstInserted.countDown();
            await(releaseFirst);
            return null;
        });
        await(firstInserted);
        Future<String> duplicate = inTransactionAsync(() -> {
            IdempotencyRecord record = idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key));
            record.complete("TEAM_COMPLETED", "{}");
            return record.getResultCode();
        });

        awaitBlockedStatements(RECORD_INSERT, 1);
        releaseFirst.countDown();
        result(first);

        assertThat(result(duplicate)).isEqualTo("TEAM_COMPLETED");
    }

    @Test
    void twoWaitersOnARolledBackKeyLeaveExactlyOneRecord() throws Exception {
        Campaign campaign = createCampaign();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<Void> first = inRolledBackTransactionAsync(() -> {
            idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", key, HASH_C));
            firstInserted.countDown();
            await(releaseFirst);
            return null;
        });
        await(firstInserted);
        List<Future<String>> waiters = List.of(
                inTransactionAsync(() -> insertRecord(campaign, key, HASH_A)),
                inTransactionAsync(() -> insertRecord(campaign, key, HASH_B)));

        awaitBlockedStatements(RECORD_INSERT, 2);
        releaseFirst.countDown();
        result(first);
        Outcomes outcomes = outcomesOf(waiters);

        assertThat(outcomes.committedHashes()).as("exactly one waiter commits").hasSize(1);
        assertThat(outcomes.failures()).singleElement()
                .satisfies(IdempotencyRecordRepositoryTests::assertDeadlockOrDuplicateKey);
        assertThat(recordCount(key)).isEqualTo(1);
        assertThat(storedRecord(key).isForRequest(outcomes.committedHashes().get(0)))
                .as("the remaining record is the committed waiter's, nothing of the rolled-back or failed one")
                .isTrue();
    }

    @Test
    void crossInsertedKeysDeadlockAndTheVictimIsReportedAsLockingFailure() throws Exception {
        Campaign campaign = createCampaign();
        CountDownLatch bothHoldTheirKey = new CountDownLatch(2);

        Outcomes outcomes = outcomesOf(List.of(
                inTransactionAsync(() -> insertOwnThenOther(campaign, key, otherKey, HASH_A, bothHoldTheirKey)),
                inTransactionAsync(() -> insertOwnThenOther(campaign, otherKey, key, HASH_B, bothHoldTheirKey))));

        assertThat(outcomes.committedHashes()).as("exactly one transaction commits").hasSize(1);
        assertThat(outcomes.failures()).singleElement().satisfies(failure -> {
            assertThat(mysqlErrorCode(failure)).isEqualTo(DEADLOCK);
            assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
        });
        String winner = outcomes.committedHashes().get(0);
        assertThat(storedRecord(key).isForRequest(winner)).isTrue();
        assertThat(storedRecord(otherKey).isForRequest(winner)).as("the victim's own insert is rolled back").isTrue();
    }

    @Test
    void purgeDeletesOnlyTheGivenCampaignInBatches() {
        Campaign closedCampaign = createCampaign();
        Campaign otherCampaign = createCampaign();
        runInTransaction(() -> {
            for (int i = 0; i < 5; i++) {
                idempotencyRecords.saveAndFlush(progressRecord(closedCampaign, "user-" + i, key));
            }
            idempotencyRecords.saveAndFlush(progressRecord(otherCampaign, "user-0", otherKey));
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

    private IdempotencyRecord progressRecord(Campaign campaign, String userId, String idempotencyKey) {
        return progressRecord(campaign, userId, idempotencyKey, HASH_A);
    }

    private IdempotencyRecord progressRecord(Campaign campaign, String userId, String idempotencyKey,
                                             String requestHash) {
        return IdempotencyRecord.start(campaign.getId(), userId, IdempotentOperation.PROGRESS, idempotencyKey,
                requestHash, NOW);
    }

    private String insertRecord(Campaign campaign, String idempotencyKey, String requestHash) {
        idempotencyRecords.saveAndFlush(progressRecord(campaign, "alice", idempotencyKey, requestHash));
        return requestHash;
    }

    private String insertOwnThenOther(Campaign campaign, String own, String other, String requestHash,
                                      CountDownLatch bothHoldTheirKey) {
        insertRecord(campaign, own, requestHash);
        bothHoldTheirKey.countDown();
        await(bothHoldTheirKey);
        return insertRecord(campaign, other, requestHash);
    }

    private IdempotencyRecord storedRecord(String idempotencyKey) {
        return idempotencyRecords
                .findByUserIdAndOperationAndIdempotencyKey("alice", IdempotentOperation.PROGRESS, idempotencyKey)
                .orElseThrow();
    }

    private int recordCount(String idempotencyKey) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_record
                WHERE user_id = 'alice' AND operation = 'PROGRESS' AND idempotency_key = ?
                """, Integer.class, idempotencyKey);
    }

    private record Outcomes(List<String> committedHashes, List<Throwable> failures) {
    }

    private static Outcomes outcomesOf(List<Future<String>> transactions) throws Exception {
        List<String> committed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (Future<String> transaction : transactions) {
            try {
                committed.add(result(transaction));
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        return new Outcomes(committed, failures);
    }

    private static void assertDeadlockOrDuplicateKey(Throwable failure) {
        int errorCode = mysqlErrorCode(failure);
        switch (errorCode) {
            case DEADLOCK -> assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
            case DUPLICATE_KEY -> assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
            default -> fail("Expected MySQL error " + DEADLOCK + " or " + DUPLICATE_KEY + " but got " + errorCode,
                    failure);
        }
    }

    private static int mysqlErrorCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getErrorCode();
            }
        }
        throw new AssertionError("No SQLException behind " + failure, failure);
    }
}
