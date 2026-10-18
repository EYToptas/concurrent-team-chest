package io.github.eytoptas.teamchest.persistence;

import static org.assertj.core.api.Assertions.fail;

import io.github.eytoptas.teamchest.TestcontainersConfiguration;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamAssignmentCursor;
import io.github.eytoptas.teamchest.domain.team.TeamMember;
import io.github.eytoptas.teamchest.persistence.campaign.CampaignRepository;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotencyRecordRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamAssignmentCursorRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamMemberRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class PersistenceIntegrationTest {

    static final Instant CAMPAIGN_START = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant CAMPAIGN_END = Instant.parse("2026-10-31T00:00:00Z");
    public static final Instant NOW = Instant.parse("2026-10-18T12:00:00.123456Z");
    static final String REWARD = "{\"coins\": 500}";

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected CampaignRepository campaigns;
    @Autowired
    protected TeamAssignmentCursorRepository cursors;
    @Autowired
    protected TeamRepository teams;
    @Autowired
    protected TeamMemberRepository members;
    @Autowired
    protected IdempotencyRecordRepository idempotencyRecords;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> createdCampaignIds = new ArrayList<>();
    private ExecutorService executor;

    @BeforeEach
    void startExecutor() {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void stopExecutorAndDeleteTestData() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        for (Long campaignId : createdCampaignIds) {
            jdbc.update("DELETE FROM idempotency_record WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM team_member WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM team_assignment_cursor WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM team WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM campaign WHERE id = ?", campaignId);
        }
    }

    protected <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    protected void runInTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    protected <T> T inRolledBackTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            T result = work.get();
            status.setRollbackOnly();
            return result;
        });
    }

    protected <T> Future<T> inTransactionAsync(Supplier<T> work) {
        return executor.submit(() -> inTransaction(work));
    }

    protected <T> Future<T> inRolledBackTransactionAsync(Supplier<T> work) {
        return executor.submit(() -> inRolledBackTransaction(work));
    }

    protected Campaign createCampaign() {
        Campaign campaign = inTransaction(() -> {
            Campaign saved = campaigns.save(new Campaign(
                    "test-" + UUID.randomUUID(), 30, 300, REWARD, CAMPAIGN_START, CAMPAIGN_END));
            cursors.save(TeamAssignmentCursor.forCampaign(saved));
            return saved;
        });
        createdCampaignIds.add(campaign.getId());
        return campaign;
    }

    protected Team createTeam(Campaign campaign) {
        return inTransaction(() -> teams.save(Team.createFor(campaign, NOW)));
    }

    protected TeamMember addMember(Team team, String userId) {
        return inTransaction(() -> {
            Team locked = teams.findByIdForUpdate(team.getId()).orElseThrow();
            return members.save(locked.addMember(userId, NOW));
        });
    }

    protected void applyPoints(Team team, String userId, int points) {
        runInTransaction(() -> {
            Team locked = teams.findByIdForUpdate(team.getId()).orElseThrow();
            TeamMember member = members.findByCampaignIdAndUserId(locked.getCampaignId(), userId).orElseThrow();
            locked.applyPoints(member, points, NOW);
        });
    }

    protected void awaitBlockedStatements(String sqlLikePattern, int expected) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (countRunningStatements(sqlLikePattern) < expected) {
            if (System.nanoTime() > deadline) {
                fail("Expected " + expected + " blocked statement(s) like '" + sqlLikePattern + "'");
            }
            sleep(Duration.ofMillis(20));
        }
    }

    private int countRunningStatements(String sqlLikePattern) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.PROCESSLIST
                WHERE INFO LIKE ? AND ID <> CONNECTION_ID()
                """, Integer.class, sqlLikePattern);
        return count == null ? 0 : count;
    }

    protected static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static <T> T result(Future<T> future) throws Exception {
        return future.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
