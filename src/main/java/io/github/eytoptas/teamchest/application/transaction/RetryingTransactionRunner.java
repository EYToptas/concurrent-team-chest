package io.github.eytoptas.teamchest.application.transaction;

import io.github.eytoptas.teamchest.application.TeamChestProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RetryingTransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetryingTransactionRunner.class);

    private final TransactionTemplate transactionTemplate;
    private final TeamChestProperties.TransactionSettings settings;

    public RetryingTransactionRunner(PlatformTransactionManager transactionManager, TeamChestProperties properties) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.settings = properties.transaction();
    }

    public <T> T execute(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= settings.maxAttempts()) {
                    log.warn("Transaction failed after {} attempts: {}", attempt, e.getMessage());
                    throw new ConcurrencyRetriesExhaustedException(attempt, e);
                }
                log.debug("Transaction attempt {} failed with a lock conflict, retrying: {}", attempt, e.getMessage());
                pauseBeforeRetry();
            }
        }
    }

    private void pauseBeforeRetry() {
        long min = settings.minBackoff().toMillis();
        long max = settings.maxBackoff().toMillis();
        long pause = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (pause <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofMillis(pause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry a transaction", e);
        }
    }
}
