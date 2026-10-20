package io.github.eytoptas.teamchest.application.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import io.github.eytoptas.teamchest.application.join.JoinBatcher.Failed;
import io.github.eytoptas.teamchest.application.join.JoinBatcher.Joined;
import io.github.eytoptas.teamchest.application.join.JoinBatcher.Outcome;
import io.github.eytoptas.teamchest.application.join.JoinBatcher.Request;
import io.github.eytoptas.teamchest.application.join.JoinBatcher.RunAlone;
import io.github.eytoptas.teamchest.application.transaction.ConcurrencyRetriesExhaustedException;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class JoinBatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final long TEAM_ID = 7;
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    private final Campaign campaign = campaign(1);
    private final ControlledHandler handler = new ControlledHandler();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private JoinBatcher batcher = batcher(50, 500, Duration.ofSeconds(5), Duration.ofSeconds(30));

    @AfterEach
    void stop() throws InterruptedException {
        handler.openGate();
        batcher.close();
        clients.shutdownNow();
        clients.awaitTermination(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void requestsArrivingDuringATransactionAreJoinedTogetherInArrivalOrder() throws Exception {
        handler.closeGate();
        Future<Outcome> first = submitAsync(campaign, "a");
        handler.awaitCalls(1);
        Future<Outcome> b = submitInOrder(campaign, "b", 1);
        Future<Outcome> c = submitInOrder(campaign, "c", 2);
        Future<Outcome> d = submitInOrder(campaign, "d", 3);

        handler.openGate();

        assertThat(outcome(first)).isEqualTo(joined("a"));
        assertThat(outcome(b)).isEqualTo(joined("b"));
        assertThat(outcome(c)).isEqualTo(joined("c"));
        assertThat(outcome(d)).isEqualTo(joined("d"));
        assertThat(handler.calls).containsExactly(new Call(1, List.of("a")), new Call(1, List.of("b", "c", "d")));
        assertThat(meters.timer("teamchest.join.queue").count()).isEqualTo(4);
        assertThat(meters.timer("teamchest.join.batch").count()).isEqualTo(2);
    }

    @Test
    void answerIsGivenOnlyAfterTheTransactionReturned() throws Exception {
        handler.closeGate();
        Future<Outcome> join = submitAsync(campaign, "a");
        handler.awaitCalls(1);

        assertThat(join.isDone()).as("answered while the transaction is still running").isFalse();
        handler.openGate();

        assertThat(outcome(join)).isEqualTo(joined("a"));
    }

    @Test
    void batchesAreLimitedToTheMaximumSize() throws Exception {
        batcher = batcher(2, 500, Duration.ofSeconds(5), Duration.ofSeconds(30));
        handler.closeGate();
        Future<Outcome> first = submitAsync(campaign, "a");
        handler.awaitCalls(1);
        List<Future<Outcome>> rest = List.of(submitInOrder(campaign, "b", 1), submitInOrder(campaign, "c", 2),
                submitInOrder(campaign, "d", 3));

        handler.openGate();

        outcome(first);
        for (Future<Outcome> join : rest) {
            assertThat(outcome(join)).isInstanceOf(Joined.class);
        }
        assertThat(handler.calls).extracting(Call::userIds)
                .containsExactly(List.of("a"), List.of("b", "c"), List.of("d"));
    }

    @Test
    void sameUserTwiceInAGroupIsJoinedOnceAndBothGetTheTeam() throws Exception {
        handler.closeGate();
        Future<Outcome> first = submitAsync(campaign, "other");
        handler.awaitCalls(1);
        Future<Outcome> phone = submitInOrder(campaign, "alice", 1);
        Future<Outcome> tablet = submitInOrder(campaign, "alice", 2);

        handler.openGate();

        outcome(first);
        assertThat(outcome(phone)).isEqualTo(new Joined(new JoinResult(1, TEAM_ID, true)));
        assertThat(outcome(tablet)).isEqualTo(new Joined(new JoinResult(1, TEAM_ID, false)));
        assertThat(handler.calls).extracting(Call::userIds).containsExactly(List.of("other"), List.of("alice"));
    }

    @Test
    void eachCampaignGetsATransactionOfItsOwn() throws Exception {
        Campaign other = campaign(2);
        handler.closeGate();
        Future<Outcome> first = submitAsync(campaign, "a");
        handler.awaitCalls(1);
        Future<Outcome> b = submitInOrder(other, "b", 1);
        Future<Outcome> c = submitInOrder(campaign, "c", 2);

        handler.openGate();

        outcome(first);
        assertThat(outcome(b)).isEqualTo(new Joined(new JoinResult(2, TEAM_ID, true)));
        assertThat(outcome(c)).isEqualTo(joined("c"));
        assertThat(handler.calls).containsExactly(
                new Call(1, List.of("a")), new Call(2, List.of("b")), new Call(1, List.of("c")));
    }

    @Test
    void fullQueueIsRejectedAtOnce() throws Exception {
        batcher = batcher(50, 2, Duration.ofSeconds(5), Duration.ofSeconds(30));
        handler.closeGate();
        Future<Outcome> running = submitAsync(campaign, "a");
        handler.awaitCalls(1);
        Future<Outcome> b = submitInOrder(campaign, "b", 1);
        Future<Outcome> c = submitInOrder(campaign, "c", 2);

        assertThatThrownBy(() -> batcher.submit(campaign, request("d"))).isInstanceOf(JoinBusyException.class);

        handler.openGate();
        outcome(running);
        assertThat(outcome(b)).isInstanceOf(Joined.class);
        assertThat(outcome(c)).isInstanceOf(Joined.class);
        assertThat(handler.userIdsSeen()).doesNotContain("d");
    }

    @Test
    void requestNotTakenInTimeIsWithdrawnAndNeverExecuted() throws Exception {
        batcher = batcher(50, 500, Duration.ofMillis(200), Duration.ofSeconds(30));
        handler.closeGate();
        Future<Outcome> running = submitAsync(campaign, "a");
        handler.awaitCalls(1);

        assertThatThrownBy(() -> batcher.submit(campaign, request("late"))).isInstanceOf(JoinBusyException.class);

        handler.openGate();
        outcome(running);
        assertThat(outcome(submitAsync(campaign, "next"))).isEqualTo(joined("next"));
        assertThat(handler.userIdsSeen()).containsExactly("a", "next");
    }

    @Test
    void requestInATransactionWaitsForItsOutcomeBeyondTheQueueTimeout() throws Exception {
        batcher = batcher(50, 500, Duration.ofMillis(100), Duration.ofSeconds(30));
        handler.closeGate();
        Future<Outcome> join = submitAsync(campaign, "a");
        handler.awaitCalls(1);
        Thread.sleep(300);

        handler.openGate();

        assertThat(outcome(join)).isEqualTo(joined("a"));
    }

    @Test
    void requestInATransactionGivesUpAfterTheResultTimeout() throws Exception {
        batcher = batcher(50, 500, Duration.ofMillis(100), Duration.ofMillis(200));
        handler.closeGate();
        Future<Outcome> join = submitAsync(campaign, "a");
        handler.awaitCalls(1);

        assertThatThrownBy(() -> outcome(join)).hasCauseInstanceOf(JoinBusyException.class);
    }

    @Test
    void failedGroupLetsEveryRequestJoinOnItsOwn() throws Exception {
        handler.closeGate();
        Future<Outcome> first = submitAsync(campaign, "a");
        handler.awaitCalls(1);
        Future<Outcome> b = submitInOrder(campaign, "b", 1);
        Future<Outcome> c = submitInOrder(campaign, "c", 2);
        handler.failWhen(requests -> requests.size() == 2
                ? new DataIntegrityViolationException("Duplicate entry for b") : null);

        handler.openGate();

        assertThat(outcome(first)).isEqualTo(joined("a"));
        assertThat(outcome(b)).isInstanceOf(RunAlone.class);
        assertThat(outcome(c)).isInstanceOf(RunAlone.class);
    }

    @Test
    void unknownCommitOutcomeLetsTheRequestJoinOnItsOwn() throws Exception {
        handler.failWhen(requests -> new IllegalStateException("Communications link failure during commit"));

        assertThat(batcher.submit(campaign, request("a"))).isInstanceOf(RunAlone.class);
    }

    @Test
    void exhaustedLockRetriesFailARequestThatWasAlone() {
        ConcurrencyRetriesExhaustedException exhausted =
                new ConcurrencyRetriesExhaustedException(3, new CannotAcquireLockException("lock wait timeout"));
        handler.failWhen(requests -> exhausted);

        assertThat(batcher.submit(campaign, request("a"))).isEqualTo(new Failed(exhausted));
    }

    @Test
    void exhaustedLockRetriesOfAGroupLetEveryRequestJoinOnItsOwn() throws Exception {
        handler.closeGate();
        Future<Outcome> first = submitAsync(campaign, "a");
        handler.awaitCalls(1);
        Future<Outcome> b = submitInOrder(campaign, "b", 1);
        Future<Outcome> c = submitInOrder(campaign, "c", 2);
        handler.failWhen(requests -> requests.size() == 2
                ? new ConcurrencyRetriesExhaustedException(3, new CannotAcquireLockException("lock wait timeout"))
                : null);

        handler.openGate();

        outcome(first);
        assertThat(outcome(b)).isInstanceOf(RunAlone.class);
        assertThat(outcome(c)).isInstanceOf(RunAlone.class);
    }

    @Test
    void errorInAGroupFailsItsRequestsAndTheNextRequestStillRuns() throws Exception {
        handler.failWhen(requests -> requests.get(0).userId().equals("a") ? new ErrorCarrier() : null);

        Outcome failed = batcher.submit(campaign, request("a"));

        assertThat(failed).isInstanceOf(Failed.class);
        assertThat(batcher.submit(campaign, request("b"))).isEqualTo(joined("b"));
    }

    @Test
    void closeAnswersWaitingRequestsAndRejectsNewOnes() throws Exception {
        handler.closeGate();
        Future<Outcome> running = submitAsync(campaign, "a");
        handler.awaitCalls(1);
        Future<Outcome> waiting = submitInOrder(campaign, "b", 1);
        Thread closer = Thread.ofPlatform().start(batcher::close);
        awaitState(closer, Thread.State.TIMED_WAITING);

        handler.openGate();
        closer.join(WAIT_LIMIT.toMillis());

        assertThat(outcome(running)).as("the running group finishes").isEqualTo(joined("a"));
        assertThat(outcome(waiting)).isInstanceOfSatisfying(Failed.class,
                failed -> assertThat(failed.exception()).isInstanceOf(JoinBusyException.class));
        assertThatThrownBy(() -> batcher.submit(campaign, request("c"))).isInstanceOf(JoinBusyException.class);
        assertThat(handler.userIdsSeen()).containsExactly("a");
    }

    private JoinBatcher batcher(int maxBatchSize, int queueCapacity, Duration queueTimeout, Duration resultTimeout) {
        if (batcher != null) {
            batcher.close();
        }
        return new JoinBatcher(handler,
                new JoinProperties(true, maxBatchSize, queueCapacity, queueTimeout, resultTimeout), meters);
    }

    private Future<Outcome> submitAsync(Campaign target, String userId) {
        return clients.submit(() -> batcher.submit(target, request(userId)));
    }

    private Future<Outcome> submitInOrder(Campaign target, String userId, int position) {
        Future<Outcome> join = submitAsync(target, userId);
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (batcher.waiting() < position) {
            if (System.nanoTime() > deadline) {
                fail("Request of " + userId + " did not reach the queue");
            }
            Thread.onSpinWait();
        }
        return join;
    }

    private static Request request(String userId) {
        return new Request(userId, NOW);
    }

    private static Joined joined(String userId) {
        return new Joined(new JoinResult(1, TEAM_ID, true));
    }

    private static Outcome outcome(Future<Outcome> join) throws Exception {
        return join.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void awaitState(Thread thread, Thread.State state) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (thread.getState() != state) {
            if (System.nanoTime() > deadline) {
                fail("Thread did not reach " + state);
            }
            Thread.onSpinWait();
        }
    }

    private static Campaign campaign(long id) {
        Campaign campaign = new Campaign("campaign-" + id, 30, 300, "{}", NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        ReflectionTestUtils.setField(campaign, "id", id);
        return campaign;
    }

    record Call(long campaignId, List<String> userIds) {
    }

    private static final class ErrorCarrier extends RuntimeException {
    }

    private static final class ControlledHandler implements JoinBatcher.BatchHandler {

        final List<Call> calls = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile Function<List<Request>, RuntimeException> failure = requests -> null;

        @Override
        public Map<String, JoinResult> joinAll(Campaign target, List<Request> requests) {
            calls.add(new Call(target.getId(), requests.stream().map(Request::userId).toList()));
            try {
                if (!gate.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Gate was not opened");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            RuntimeException problem = failure.apply(requests);
            if (problem instanceof ErrorCarrier) {
                throw new AssertionError("simulated error in the group transaction");
            }
            if (problem != null) {
                throw problem;
            }
            Map<String, JoinResult> results = new LinkedHashMap<>();
            for (Request request : requests) {
                results.put(request.userId(), new JoinResult(target.getId(), TEAM_ID, true));
            }
            return results;
        }

        void closeGate() {
            gate = new CountDownLatch(1);
        }

        void openGate() {
            gate.countDown();
        }

        void failWhen(Function<List<Request>, RuntimeException> rule) {
            failure = rule;
        }

        List<String> userIdsSeen() {
            return calls.stream().flatMap(call -> call.userIds().stream()).toList();
        }

        void awaitCalls(int expected) {
            long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
            while (calls.size() < expected) {
                if (System.nanoTime() > deadline) {
                    fail("Expected " + expected + " group transaction(s)");
                }
                Thread.onSpinWait();
            }
        }
    }
}
