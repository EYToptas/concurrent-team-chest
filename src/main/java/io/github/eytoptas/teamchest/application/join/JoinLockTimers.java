package io.github.eytoptas.teamchest.application.join;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class JoinLockTimers {

    private final Timer lockWait;
    private final Timer lockWork;
    private final Timer commit;
    private final Timer lockHeld;

    JoinLockTimers(MeterRegistry meters) {
        this.lockWait = Timer.builder("teamchest.join.lock.wait")
                .description("Join transaction: wait for the campaign's cursor row lock")
                .register(meters);
        this.lockWork = Timer.builder("teamchest.join.lock.work")
                .description("Join transaction: work under the cursor lock until the commit starts")
                .register(meters);
        this.commit = Timer.builder("teamchest.join.commit")
                .description("Join transaction: flush and durable COMMIT")
                .register(meters);
        this.lockHeld = Timer.builder("teamchest.join.lock.held")
                .description("Join transaction: cursor lock held, until commit or rollback")
                .register(meters);
    }

    <T> T timeLock(Supplier<T> lock) {
        long started = System.nanoTime();
        T locked;
        try {
            locked = lock.get();
        } finally {
            lockWait.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new LockHolding(System.nanoTime()));
        }
        return locked;
    }

    private final class LockHolding implements TransactionSynchronization {

        private final long lockedNanos;
        private long commitStartedNanos;
        private boolean released;

        LockHolding(long lockedNanos) {
            this.lockedNanos = lockedNanos;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            commitStartedNanos = System.nanoTime();
            lockWork.record(commitStartedNanos - lockedNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public void afterCommit() {
            long now = System.nanoTime();
            commit.record(now - commitStartedNanos, TimeUnit.NANOSECONDS);
            lockHeld.record(now - lockedNanos, TimeUnit.NANOSECONDS);
            released = true;
        }

        @Override
        public void afterCompletion(int status) {
            if (!released) {
                lockHeld.record(System.nanoTime() - lockedNanos, TimeUnit.NANOSECONDS);
            }
        }
    }
}
