package io.github.eytoptas.teamchest.application.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import io.github.eytoptas.teamchest.application.AfterCommitRecorder.Observation;
import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.TeamChestMetrics;
import io.github.eytoptas.teamchest.application.cache.TeamStateChanged;
import io.github.eytoptas.teamchest.application.campaign.CampaignCatalog;
import io.github.eytoptas.teamchest.application.campaign.NoActiveCampaignException;
import io.github.eytoptas.teamchest.application.transaction.ConcurrencyRetriesExhaustedException;
import io.github.eytoptas.teamchest.application.transaction.RetryingTransactionRunner;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

class JoinServiceTests extends ServiceIntegrationTest {

    private static final String CURSOR_LOCK = "%from team_assignment_cursor%for update%";
    private static final String MEMBER_INSERT = "%insert into team_member%";
    private static final JoinProperties DEFAULTS =
            new JoinProperties(true, 50, 500, Duration.ofSeconds(5), Duration.ofSeconds(30));

    @Autowired
    private CampaignCatalog campaignCatalog;
    @Autowired
    private RetryingTransactionRunner transactions;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private Clock clock;

    private final List<JoinService> ownServices = new ArrayList<>();

    @AfterEach
    void stopOwnServices() {
        ownServices.forEach(JoinService::destroy);
    }

    @Test
    void firstJoinOpensATeamAndTheNextJoinsFillIt() {
        Campaign campaign = activeCampaign();

        JoinResult first = joinService.join("alice");
        JoinResult second = joinService.join("bob");

        assertThat(first.newlyJoined()).isTrue();
        assertThat(first.campaignId()).isEqualTo(campaign.getId());
        assertThat(second.teamId()).isEqualTo(first.teamId());
        assertThat(fillingTeamId(campaign)).isEqualTo(first.teamId());
        assertThat(teamSizes(campaign)).containsExactly(2);
    }

    @Test
    void joiningAgainReturnsTheExistingTeam() {
        activeCampaign();
        JoinResult first = joinService.join("alice");

        JoinResult again = joinService.join("alice");

        assertThat(again).isEqualTo(new JoinResult(first.campaignId(), first.teamId(), false));
    }

    @Test
    void thirtyFirstPlayerOpensTheNextTeam() {
        Campaign campaign = activeCampaign();
        IntStream.rangeClosed(1, 30).forEach(i -> joinService.join("player-" + i));
        assertThat(fillingTeamId(campaign)).as("a full team is released").isNull();

        JoinResult thirtyFirst = joinService.join("player-31");

        assertThat(teamSizes(campaign)).containsExactly(30, 1);
        assertThat(fillingTeamId(campaign)).isEqualTo(thirtyFirst.teamId());
    }

    @Test
    void concurrentJoinsFillTeamsOneAtATime() throws Exception {
        Campaign campaign = activeCampaign();

        List<Future<JoinResult>> joins = new ArrayList<>();
        for (int i = 1; i <= 146; i++) {
            String userId = "player-" + i;
            joins.add(async(() -> joinService.join(userId)));
        }
        for (Future<JoinResult> join : joins) {
            assertThat(result(join).newlyJoined()).isTrue();
        }

        assertThat(teamSizes(campaign)).containsExactly(30, 30, 30, 30, 26);
        assertThat(memberRowsPerTeam(campaign)).containsExactly(30, 30, 30, 30, 26);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM team_member WHERE campaign_id = ?",
                Integer.class, campaign.getId())).isEqualTo(146);
        assertThat(fillingTeamId(campaign)).isEqualTo(lastTeamId(campaign));
    }

    @Test
    void sameUserJoiningFromSeveralDevicesAtOnceGetsOneMembership() throws Exception {
        Campaign campaign = activeCampaign();

        List<Future<JoinResult>> devices = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            devices.add(async(() -> joinService.join("alice")));
        }
        List<JoinResult> results = new ArrayList<>();
        for (Future<JoinResult> device : devices) {
            results.add(result(device));
        }

        assertThat(results).extracting(JoinResult::teamId).containsOnly(results.get(0).teamId());
        assertThat(results).filteredOn(JoinResult::newlyJoined).hasSize(1);
        assertThat(teamSizes(campaign)).containsExactly(1);
    }

    @Test
    void joinIsRejectedWhenNoCampaignAcceptsJoins() {
        assertThatThrownBy(() -> joinService.join("alice")).isInstanceOf(NoActiveCampaignException.class);

        endedCampaignInGracePeriod();

        assertThatThrownBy(() -> joinService.join("alice")).isInstanceOf(NoActiveCampaignException.class);
    }

    @Test
    void successfulJoinIsAnnouncedAfterCommitOnly() {
        activeCampaign();

        JoinResult joined = joinService.join("alice");
        joinService.join("alice");

        assertThat(afterCommit.forTeam(joined.teamId())).singleElement().satisfies(observation -> {
            assertThat(observation.campaign()).isEqualTo(new TeamStateChanged(joined.campaignId(), joined.teamId(), 1));
            assertThat(observation.committedVersion()).isEqualTo(1);
        });
    }

    @Test
    void oneGroupFillsTheTeamToThirtyAndOpensTheNextTeam() throws Exception {
        Campaign campaign = activeCampaign();
        IntStream.rangeClosed(1, 28).forEach(i -> joinService.join("player-" + i));
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> holder = holdCursorLock(campaign, release);
        Future<JoinResult> twentyNinth = async(() -> joinService.join("player-29"));
        awaitBlockedStatements(CURSOR_LOCK, 1);
        Future<JoinResult> thirtieth = joinInQueue(joinService, "player-30", 1);
        Future<JoinResult> thirtyFirst = joinInQueue(joinService, "player-31", 2);
        Future<JoinResult> thirtySecond = joinInQueue(joinService, "player-32", 3);

        release.countDown();
        result(holder);

        long fullTeam = result(twentyNinth).teamId();
        assertThat(result(thirtieth)).isEqualTo(new JoinResult(campaign.getId(), fullTeam, true));
        long nextTeam = result(thirtyFirst).teamId();
        assertThat(nextTeam).isNotEqualTo(fullTeam);
        assertThat(result(thirtySecond)).isEqualTo(new JoinResult(campaign.getId(), nextTeam, true));
        assertThat(teamSizes(campaign)).containsExactly(30, 2);
        assertThat(memberRowsPerTeam(campaign)).containsExactly(30, 2);
        assertThat(fillingTeamId(campaign)).isEqualTo(nextTeam);
        assertThat(afterCommit.forTeam(fullTeam)).last().isEqualTo(
                new Observation(new TeamStateChanged(campaign.getId(), fullTeam, 30), 30));
        assertThat(afterCommit.forTeam(nextTeam)).containsExactly(
                new Observation(new TeamStateChanged(campaign.getId(), nextTeam, 2), 2));
    }

    @Test
    void sameUserTwiceInOneGroupGetsOneMembership() throws Exception {
        Campaign campaign = activeCampaign();
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> holder = holdCursorLock(campaign, release);
        Future<JoinResult> first = async(() -> joinService.join("first"));
        awaitBlockedStatements(CURSOR_LOCK, 1);
        Future<JoinResult> phone = joinInQueue(joinService, "alice", 1);
        Future<JoinResult> other = joinInQueue(joinService, "bob", 2);
        Future<JoinResult> tablet = joinInQueue(joinService, "alice", 3);

        release.countDown();
        result(holder);

        long teamId = result(first).teamId();
        assertThat(result(phone)).isEqualTo(new JoinResult(campaign.getId(), teamId, true));
        assertThat(result(tablet)).isEqualTo(new JoinResult(campaign.getId(), teamId, false));
        assertThat(result(other)).isEqualTo(new JoinResult(campaign.getId(), teamId, true));
        assertThat(teamSizes(campaign)).containsExactly(3);
        assertThat(memberRowsPerTeam(campaign)).containsExactly(3);
        assertThat(afterCommit.forTeam(teamId)).extracting(observation -> observation.campaign().version())
                .containsExactly(1L, 3L);
    }

    @Test
    void failedGroupIsRolledBackAndEveryUserThenJoinsOnItsOwn() throws Exception {
        Campaign campaign = activeCampaign();
        Team sideTeam = createTeam(campaign);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> holder = holdCursorLock(campaign, release);
        Future<JoinResult> first = async(() -> joinService.join("first"));
        awaitBlockedStatements(CURSOR_LOCK, 1);
        Future<JoinResult> a = joinInQueue(joinService, "a", 1);
        Future<JoinResult> poison = joinInQueue(joinService, "poison", 2);
        Future<JoinResult> b = joinInQueue(joinService, "b", 3);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commitPoison = new CountDownLatch(1);
        Future<Void> writer = inTransactionAsync(() -> {
            Team locked = teams.findByIdForUpdate(sideTeam.getId()).orElseThrow();
            members.save(locked.addMember("poison", NOW));
            inserted.countDown();
            await(commitPoison);
            return null;
        });
        await(inserted);

        release.countDown();
        result(holder);
        long mainTeam = result(first).teamId();
        awaitBlockedStatements(MEMBER_INSERT, 1);
        commitPoison.countDown();
        result(writer);

        assertThat(result(poison)).isEqualTo(new JoinResult(campaign.getId(), sideTeam.getId(), false));
        assertThat(result(a)).isEqualTo(new JoinResult(campaign.getId(), mainTeam, true));
        assertThat(result(b)).isEqualTo(new JoinResult(campaign.getId(), mainTeam, true));
        assertThat(jdbc.queryForObject("SELECT state_seq FROM team WHERE id = ?", Long.class, mainTeam)).isEqualTo(3);
        assertThat(teamSizes(campaign)).containsExactly(1, 3);
        assertThat(memberRowsPerTeam(campaign)).containsExactly(1, 3);
        assertThat(fillingTeamId(campaign)).isEqualTo(mainTeam);
        assertThat(afterCommit.forTeam(mainTeam)).extracting(observation -> observation.campaign().version())
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void concurrentJoinsOfManyUsersOnSeveralDevicesGiveOneMembershipEach() throws Exception {
        Campaign campaign = activeCampaign();
        Map<String, List<Future<JoinResult>>> joins = new LinkedHashMap<>();
        for (int device = 0; device < 3; device++) {
            for (int i = 1; i <= 50; i++) {
                String userId = "player-" + i;
                joins.computeIfAbsent(userId, id -> new ArrayList<>()).add(async(() -> joinService.join(userId)));
            }
        }

        for (Map.Entry<String, List<Future<JoinResult>>> user : joins.entrySet()) {
            List<JoinResult> results = new ArrayList<>();
            for (Future<JoinResult> join : user.getValue()) {
                results.add(result(join));
            }
            assertThat(results).as(user.getKey()).extracting(JoinResult::teamId).containsOnly(results.get(0).teamId());
            assertThat(results).as(user.getKey()).filteredOn(JoinResult::newlyJoined).hasSize(1);
        }
        assertThat(teamSizes(campaign)).containsExactly(30, 20);
        assertThat(memberRowsPerTeam(campaign)).containsExactly(30, 20);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM team_member WHERE campaign_id = ?",
                Integer.class, campaign.getId())).isEqualTo(50);
        assertThat(fillingTeamId(campaign)).isEqualTo(lastTeamId(campaign));
    }

    @Test
    void joinThatNeverGetsTheCursorLockFailsWithoutAMembership() throws Exception {
        Campaign campaign = activeCampaign();
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> holder = holdCursorLock(campaign, release);

        assertThatThrownBy(() -> joinService.join("alice")).isInstanceOf(ConcurrencyRetriesExhaustedException.class);

        release.countDown();
        result(holder);
        assertThat(members.findTeamIdByCampaignIdAndUserId(campaign.getId(), "alice")).isEmpty();
        assertThat(teamSizes(campaign)).isEmpty();
        assertThat(joinService.join("alice").newlyJoined()).isTrue();
    }

    @Test
    void joinNotStartedWithinTheQueueTimeoutIsNeverExecuted() throws Exception {
        Campaign campaign = activeCampaign();
        JoinService service = ownService(new JoinProperties(true, 50, 500, Duration.ofMillis(300), Duration.ofSeconds(30)),
                new SimpleMeterRegistry());
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> holder = holdCursorLock(campaign, release);
        Future<JoinResult> first = async(() -> service.join("first"));
        awaitBlockedStatements(CURSOR_LOCK, 1);

        assertThatThrownBy(() -> service.join("late")).isInstanceOf(JoinBusyException.class);

        release.countDown();
        result(holder);
        result(first);
        assertThat(service.join("next").newlyJoined()).isTrue();
        assertThat(members.findTeamIdByCampaignIdAndUserId(campaign.getId(), "late")).isEmpty();
        assertThat(teamSizes(campaign)).containsExactly(2);
    }

    @Test
    void lockWaitWorkUnderTheLockAndCommitAreTimedSeparately() {
        activeCampaign();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JoinService service = ownService(DEFAULTS, meters);

        service.join("alice");
        service.join("bob");
        service.join("alice");

        for (String name : List.of("teamchest.join.lock.wait", "teamchest.join.lock.work", "teamchest.join.commit",
                "teamchest.join.lock.held", "teamchest.join.queue", "teamchest.join.batch")) {
            assertThat(meters.get(name).timer().count()).as(name).isEqualTo(2);
        }
        Timer held = meters.get("teamchest.join.lock.held").timer();
        Timer commit = meters.get("teamchest.join.commit").timer();
        Timer work = meters.get("teamchest.join.lock.work").timer();
        assertThat(held.totalTime(TimeUnit.NANOSECONDS))
                .isGreaterThanOrEqualTo(commit.totalTime(TimeUnit.NANOSECONDS))
                .isGreaterThanOrEqualTo(work.totalTime(TimeUnit.NANOSECONDS));
    }

    @Test
    void withoutBatchingConcurrentJoinsStillFillTeamsOneAtATime() throws Exception {
        Campaign campaign = activeCampaign();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JoinService service = ownService(new JoinProperties(false, 50, 500, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                meters);

        List<Future<JoinResult>> joins = new ArrayList<>();
        for (int i = 1; i <= 146; i++) {
            String userId = "player-" + i;
            joins.add(async(() -> service.join(userId)));
        }
        for (Future<JoinResult> join : joins) {
            assertThat(result(join).newlyJoined()).isTrue();
        }

        assertThat(teamSizes(campaign)).containsExactly(30, 30, 30, 30, 26);
        assertThat(memberRowsPerTeam(campaign)).containsExactly(30, 30, 30, 30, 26);
        assertThat(meters.find("teamchest.join.batch").timer()).isNull();
        assertThat(meters.get("teamchest.join.commit").timer().count()).isEqualTo(146);
    }

    private JoinService ownService(JoinProperties settings, MeterRegistry meters) {
        JoinService service = new JoinService(campaignCatalog, cursors, teams, members, transactions, eventPublisher,
                clock, settings, meters, new TeamChestMetrics(meters));
        ownServices.add(service);
        return service;
    }

    private Future<Void> holdCursorLock(Campaign campaign, CountDownLatch release) {
        CountDownLatch locked = new CountDownLatch(1);
        Future<Void> holder = inTransactionAsync(() -> {
            cursors.findByCampaignIdForUpdate(campaign.getId()).orElseThrow();
            locked.countDown();
            await(release);
            return null;
        });
        await(locked);
        return holder;
    }

    private Future<JoinResult> joinInQueue(JoinService service, String userId, int position) {
        Future<JoinResult> join = async(() -> service.join(userId));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (service.queuedJoins() < position) {
            if (System.nanoTime() > deadline) {
                fail("The join of " + userId + " did not reach the batcher");
            }
            Thread.onSpinWait();
        }
        return join;
    }

    private List<Integer> teamSizes(Campaign campaign) {
        return jdbc.queryForList("SELECT member_count FROM team WHERE campaign_id = ? ORDER BY id",
                Integer.class, campaign.getId());
    }

    private List<Integer> memberRowsPerTeam(Campaign campaign) {
        return jdbc.queryForList("""
                SELECT COUNT(*) FROM team_member WHERE campaign_id = ? GROUP BY team_id ORDER BY team_id
                """, Integer.class, campaign.getId());
    }

    private Long fillingTeamId(Campaign campaign) {
        return jdbc.queryForObject("SELECT filling_team_id FROM team_assignment_cursor WHERE campaign_id = ?",
                Long.class, campaign.getId());
    }

    private Long lastTeamId(Campaign campaign) {
        return jdbc.queryForObject("SELECT MAX(id) FROM team WHERE campaign_id = ?", Long.class, campaign.getId());
    }
}
