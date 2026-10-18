package io.github.eytoptas.teamchest.persistence.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamAssignmentCursor;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;

class TeamAssignmentCursorRepositoryTests extends PersistenceIntegrationTest {

    private static final String CURSOR_LOCK = "%from team_assignment_cursor%for update%";

    @Test
    void secondJoinWaitsForTheCursorAndSeesTheFirstJoinsCommittedChange() throws Exception {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<Void> first = inTransactionAsync(() -> {
            TeamAssignmentCursor cursor = cursors.findByCampaignIdForUpdate(campaign.getId()).orElseThrow();
            cursor.startFilling(team);
            firstHoldsLock.countDown();
            await(releaseFirst);
            return null;
        });
        await(firstHoldsLock);
        Future<Optional<Long>> second = inTransactionAsync(() ->
                cursors.findByCampaignIdForUpdate(campaign.getId()).orElseThrow().fillingTeamId());

        awaitBlockedStatements(CURSOR_LOCK, 1);
        assertThat(second).isNotDone();

        releaseFirst.countDown();
        result(first);
        assertThat(result(second)).contains(team.getId());
    }

    @Test
    void lockWaitTimeoutSurfacesAsPessimisticLockingFailure() throws Exception {
        Campaign campaign = createCampaign();
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<Void> first = inTransactionAsync(() -> {
            cursors.findByCampaignIdForUpdate(campaign.getId()).orElseThrow();
            firstHoldsLock.countDown();
            await(releaseFirst);
            return null;
        });
        await(firstHoldsLock);

        try {
            long started = System.nanoTime();
            Future<TeamAssignmentCursor> second = inTransactionAsync(() ->
                    cursors.findByCampaignIdForUpdate(campaign.getId()).orElseThrow());

            assertThatThrownBy(() -> result(second))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(PessimisticLockingFailureException.class);
            long waitedMillis = (System.nanoTime() - started) / 1_000_000;
            assertThat(waitedMillis).as("innodb_lock_wait_timeout = 2 s").isBetween(1_500L, 5_000L);
        } finally {
            releaseFirst.countDown();
            result(first);
        }
    }

    @Test
    void lockingReadOutsideATransactionIsRejected() {
        Campaign campaign = createCampaign();

        assertThatThrownBy(() -> cursors.findByCampaignIdForUpdate(campaign.getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
