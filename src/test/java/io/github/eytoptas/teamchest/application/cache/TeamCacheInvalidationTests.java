package io.github.eytoptas.teamchest.application.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.query.TeamOverview;
import io.github.eytoptas.teamchest.application.query.TeamQueryService;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

class TeamCacheInvalidationTests extends ServiceIntegrationTest {

    @Autowired
    private TeamQueryService queryService;
    @Autowired
    private TeamStandingCache cache;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private Campaign campaign;

    @BeforeEach
    void openCampaign() {
        campaign = activeCampaign();
    }

    @Test
    void aCommittedChangeInvalidatesTheCachedTeam() {
        long teamId = joinService.join("alice").teamId();
        TeamOverview before = queryService.overviewFor("alice");
        assertThat(cache.find(campaign.getId(), teamId)).as("filled by /get").contains(before.team());

        progress(campaign, "alice", 6);

        assertThat(cache.find(campaign.getId(), teamId)).as("tombstone after the commit").isEmpty();
        TeamOverview after = queryService.overviewFor("alice");
        assertThat(after.me().points()).isEqualTo(6);
        assertThat(after.team().version()).isGreaterThan(before.team().version());
        assertThat(cache.find(campaign.getId(), teamId)).contains(after.team());
    }

    @Test
    void theInvalidationRunsOnlyAfterTheCommit() {
        long teamId = joinService.join("alice").teamId();
        TeamOverview.TeamStanding cached = queryService.overviewFor("alice").team();

        runInTransaction(() -> {
            eventPublisher.publishEvent(new TeamStateChanged(campaign.getId(), teamId, cached.version() + 1));
            assertThat(cache.find(campaign.getId(), teamId)).as("still cached before the commit").contains(cached);
        });

        assertThat(cache.find(campaign.getId(), teamId)).isEmpty();
    }

    @Test
    void aRolledBackChangeDoesNotInvalidate() {
        long teamId = joinService.join("alice").teamId();
        TeamOverview.TeamStanding cached = queryService.overviewFor("alice").team();

        inRolledBackTransaction(() -> {
            eventPublisher.publishEvent(new TeamStateChanged(campaign.getId(), teamId, cached.version() + 1));
            return null;
        });

        assertThat(cache.find(campaign.getId(), teamId)).contains(cached);
    }

    @Test
    void aSlowReaderCannotOverwriteAChangeCommittedMeanwhile() {
        long teamId = joinService.join("alice").teamId();
        TeamOverview.TeamStanding readBeforeTheChange = queryService.overviewFor("alice").team();

        progress(campaign, "alice", 6);
        cache.offer(campaign.getId(), readBeforeTheChange);

        assertThat(cache.find(campaign.getId(), teamId)).as("old snapshot rejected").isEmpty();
        assertThat(queryService.overviewFor("alice").me().points()).isEqualTo(6);
    }

    @Test
    void concurrentReadsAndWritesLeaveNoStaleEntry() throws Exception {
        List<String> players = IntStream.range(0, 6).mapToObj(i -> "player-" + i).toList();
        players.forEach(joinService::join);
        long teamId = members.findTeamIdByCampaignIdAndUserId(campaign.getId(), players.get(0)).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> tasks = new ArrayList<>();
        for (String player : players) {
            tasks.add(async(() -> {
                await(start);
                for (int i = 0; i < 5; i++) {
                    progress(campaign, player, 1);
                }
                return null;
            }));
            tasks.add(async(() -> {
                await(start);
                for (int i = 0; i < 10; i++) {
                    queryService.overviewFor(player);
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<Void> task : tasks) {
            result(task);
        }

        long committedVersion = jdbc.queryForObject("SELECT state_seq FROM team WHERE id = ?", Long.class, teamId);
        Optional<TeamOverview.TeamStanding> cached = cache.find(campaign.getId(), teamId);
        cached.ifPresent(standing -> assertThat(standing.version()).isEqualTo(committedVersion));
        TeamOverview.TeamStanding served = queryService.overviewFor(players.get(0)).team();
        assertThat(served.version()).isEqualTo(committedVersion);
        assertThat(served.teamPoints()).isEqualTo(30).isEqualTo(teamPoints(teamId));
    }

    @Test
    void aJoinInvalidatesTheTeamForItsExistingMembers() {
        joinService.join("alice");
        assertThat(queryService.overviewFor("alice").team().memberCount()).isEqualTo(1);

        joinService.join("bob");

        assertThat(queryService.overviewFor("alice").team().members())
                .extracting(TeamOverview.MemberStanding::userId)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void aCachedTeamWithoutTheCallerIsReloaded() {
        long teamId = joinService.join("alice").teamId();
        queryService.overviewFor("alice");
        Team team = teams.findById(teamId).orElseThrow();
        addMember(team, "bob");

        TeamOverview bob = queryService.overviewFor("bob");

        assertThat(bob.me().userId()).isEqualTo("bob");
        assertThat(bob.team().memberCount()).isEqualTo(2);
        assertThat(cache.find(campaign.getId(), teamId)).contains(bob.team());
    }

    @Test
    void aLostInvalidationServesThePreviousVersionUntilTheNextChange() {
        long teamId = joinService.join("alice").teamId();
        assertThat(queryService.overviewFor("alice").me().points()).isZero();
        applyPoints(teams.findById(teamId).orElseThrow(), "alice", 4);

        assertThat(queryService.overviewFor("alice").me().points()).as("previous committed version").isZero();

        progress(campaign, "alice", 2);
        assertThat(queryService.overviewFor("alice").me().points()).as("healed by the next change").isEqualTo(6);
    }
}
