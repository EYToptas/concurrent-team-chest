package io.github.eytoptas.teamchest.application.join;

import io.github.eytoptas.teamchest.application.transaction.ConcurrencyRetriesExhaustedException;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JoinBatcher implements AutoCloseable {

    record Request(String userId, Instant requestedAt) {

        Request {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }
    }

    sealed interface Outcome permits Joined, RunAlone, Failed {
    }

    record Joined(JoinResult result) implements Outcome {
    }

    record RunAlone(RuntimeException cause) implements Outcome {
    }

    record Failed(RuntimeException exception) implements Outcome {
    }

    @FunctionalInterface
    interface BatchHandler {
        Map<String, JoinResult> joinAll(Campaign campaign, List<Request> requests);
    }

    private static final Logger log = LoggerFactory.getLogger(JoinBatcher.class);
    private static final int QUEUED = 0;
    private static final int TAKEN = 1;
    private static final int WITHDRAWN = 2;
    private static final long POLL_MILLIS = 200;
    private static final long CLOSE_WAIT_MILLIS = 30_000;

    private final BatchHandler handler;
    private final JoinProperties settings;
    private final ArrayBlockingQueue<Pending> queue;
    private final Timer queueWait;
    private final Timer batchTime;
    private final Object lifecycle = new Object();
    private Thread worker;
    private volatile boolean closed;

    JoinBatcher(BatchHandler handler, JoinProperties settings, MeterRegistry meters) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.queue = new ArrayBlockingQueue<>(settings.queueCapacity());
        this.queueWait = Timer.builder("teamchest.join.queue")
                .description("Time a /join waited in this instance until its group transaction started")
                .register(meters);
        this.batchTime = Timer.builder("teamchest.join.batch")
                .description("One group join transaction, lock retries and commit included")
                .register(meters);
    }

    Outcome submit(Campaign campaign, Request request) {
        ensureWorker();
        Pending pending = new Pending(campaign, request, System.nanoTime());
        if (!queue.offer(pending)) {
            throw new JoinBusyException("Too many joins are waiting in this instance");
        }
        try {
            return pending.outcome.get(settings.queueTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (pending.state.compareAndSet(QUEUED, WITHDRAWN)) {
                throw new JoinBusyException("The join did not start within " + settings.queueTimeout());
            }
            return awaitTaken(pending);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (pending.state.compareAndSet(QUEUED, WITHDRAWN)) {
                throw new JoinBusyException("Interrupted while waiting to join");
            }
            throw new JoinBusyException("Interrupted while the join was running; a retry returns the membership");
        } catch (ExecutionException e) {
            throw new IllegalStateException("Join outcome failed unexpectedly", e.getCause());
        }
    }

    int waiting() {
        return queue.size();
    }

    @Override
    public void close() {
        closed = true;
        Thread running;
        synchronized (lifecycle) {
            running = worker;
        }
        if (running != null && running != Thread.currentThread()) {
            try {
                running.join(CLOSE_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        withdrawWaiting();
    }

    private Outcome awaitTaken(Pending pending) {
        try {
            return pending.outcome.get(settings.resultTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new JoinBusyException("The join did not finish within " + settings.resultTimeout()
                    + "; it may still complete, and a retry returns the membership");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JoinBusyException("Interrupted while the join was running; a retry returns the membership");
        } catch (ExecutionException e) {
            throw new IllegalStateException("Join outcome failed unexpectedly", e.getCause());
        }
    }

    private void ensureWorker() {
        synchronized (lifecycle) {
            if (closed) {
                throw new JoinBusyException("This instance is shutting down");
            }
            if (worker == null || !worker.isAlive()) {
                worker = Thread.ofPlatform().daemon(true).name("team-chest-join-batcher").start(this::run);
            }
        }
    }

    private void run() {
        List<Pending> drained = new ArrayList<>();
        while (!closed) {
            Pending first;
            try {
                first = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            if (first == null) {
                continue;
            }
            drained.clear();
            drained.add(first);
            queue.drainTo(drained, settings.maxBatchSize() - 1);
            Map<Long, List<Pending>> byCampaign = new LinkedHashMap<>();
            for (Pending pending : drained) {
                if (pending.state.compareAndSet(QUEUED, TAKEN)) {
                    byCampaign.computeIfAbsent(pending.campaign.getId(), id -> new ArrayList<>()).add(pending);
                }
            }
            for (List<Pending> group : byCampaign.values()) {
                runGroup(group);
            }
        }
        withdrawWaiting();
    }

    private void runGroup(List<Pending> group) {
        long started = System.nanoTime();
        for (Pending pending : group) {
            queueWait.record(started - pending.enqueuedNanos, TimeUnit.NANOSECONDS);
        }
        Map<String, Request> byUser = new LinkedHashMap<>();
        for (Pending pending : group) {
            byUser.putIfAbsent(pending.request.userId(), pending.request);
        }
        List<Request> requests = List.copyOf(byUser.values());
        Map<String, JoinResult> results;
        try {
            results = handler.joinAll(group.get(0).campaign, requests);
        } catch (ConcurrencyRetriesExhaustedException e) {
            if (requests.size() == 1) {
                completeAll(group, new Failed(e));
            } else {
                log.warn("Group join of {} users exhausted its lock retries; each joins on its own", requests.size());
                completeAll(group, new RunAlone(e));
            }
            return;
        } catch (RuntimeException e) {
            log.warn("Group join of {} users failed; each joins on its own: {}", requests.size(), e.toString());
            completeAll(group, new RunAlone(e));
            return;
        } catch (Error e) {
            log.error("Group join of {} users failed", requests.size(), e);
            completeAll(group, new Failed(new IllegalStateException("Group join failed", e)));
            return;
        } finally {
            batchTime.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
        Set<String> answered = new HashSet<>();
        for (Pending pending : group) {
            String userId = pending.request.userId();
            JoinResult result = results.get(userId);
            if (result == null) {
                pending.outcome.complete(new RunAlone(new IllegalStateException("No group result for " + userId)));
            } else if (answered.add(userId)) {
                pending.outcome.complete(new Joined(result));
            } else {
                pending.outcome.complete(new Joined(new JoinResult(result.campaignId(), result.teamId(), false)));
            }
        }
    }

    private static void completeAll(List<Pending> group, Outcome outcome) {
        for (Pending pending : group) {
            pending.outcome.complete(outcome);
        }
    }

    private void withdrawWaiting() {
        Pending pending;
        while ((pending = queue.poll()) != null) {
            if (pending.state.compareAndSet(QUEUED, WITHDRAWN)) {
                pending.outcome.complete(new Failed(new JoinBusyException("This instance is shutting down")));
            }
        }
    }

    private static final class Pending {

        final Campaign campaign;
        final Request request;
        final long enqueuedNanos;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        final CompletableFuture<Outcome> outcome = new CompletableFuture<>();

        Pending(Campaign campaign, Request request, long enqueuedNanos) {
            this.campaign = Objects.requireNonNull(campaign, "campaign");
            this.request = Objects.requireNonNull(request, "request");
            this.enqueuedNanos = enqueuedNanos;
        }
    }
}
