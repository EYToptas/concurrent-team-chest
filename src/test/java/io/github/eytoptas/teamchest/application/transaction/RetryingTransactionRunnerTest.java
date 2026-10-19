package io.github.eytoptas.teamchest.application.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.TeamChestProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RetryingTransactionRunnerTest {

    private final CountingTransactionManager transactionManager = new CountingTransactionManager();
    private final RetryingTransactionRunner runner = new RetryingTransactionRunner(transactionManager,
            new TeamChestProperties(new TeamChestProperties.CampaignSettings(Duration.ofDays(7)),
                    new TeamChestProperties.TransactionSettings(3, Duration.ZERO, Duration.ZERO)));

    @Test
    void successfulWorkRunsInOneCommittedTransaction() {
        assertThat(runner.execute(() -> "done")).isEqualTo("done");

        assertThat(transactionManager.begun).isEqualTo(1);
        assertThat(transactionManager.committed).isEqualTo(1);
        assertThat(transactionManager.rolledBack).isZero();
    }

    @Test
    void lockConflictIsRetriedEachTimeInANewTransaction() {
        AtomicInteger calls = new AtomicInteger();

        String result = runner.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new CannotAcquireLockException("deadlock");
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(transactionManager.begun).isEqualTo(3);
        assertThat(transactionManager.rolledBack).isEqualTo(2);
        assertThat(transactionManager.committed).isEqualTo(1);
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfAttempts() {
        assertThatThrownBy(() -> runner.execute(() -> {
            throw new PessimisticLockingFailureException("lock wait timeout");
        }))
                .isInstanceOf(ConcurrencyRetriesExhaustedException.class)
                .hasCauseInstanceOf(PessimisticLockingFailureException.class);

        assertThat(transactionManager.begun).isEqualTo(3);
        assertThat(transactionManager.rolledBack).isEqualTo(3);
        assertThat(transactionManager.committed).isZero();
    }

    @Test
    void otherFailuresAreNotRetried() {
        assertThatThrownBy(() -> runner.execute(() -> {
            throw new DataIntegrityViolationException("constraint");
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(transactionManager.begun).isEqualTo(1);
        assertThat(transactionManager.rolledBack).isEqualTo(1);
    }

    private static final class CountingTransactionManager implements PlatformTransactionManager {

        int begun;
        int committed;
        int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }
}
