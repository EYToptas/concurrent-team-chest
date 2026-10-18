package io.github.eytoptas.teamchest.persistence.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.ProgressOutcome;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamAssignmentCursor;
import io.github.eytoptas.teamchest.domain.team.TeamMember;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

class TeamRepositoryTests extends PersistenceIntegrationTest {

    private static final String TEAM_LOCK = "%from team %for update%";

    @Test
    void newTeamGetsItsIdImmediatelySoMembersCanJoinInTheSameTransaction() {
        Campaign campaign = createCampaign();

        runInTransaction(() -> {
            TeamAssignmentCursor cursor = cursors.findByCampaignIdForUpdate(campaign.getId()).orElseThrow();
            Team team = teams.save(Team.createFor(campaign, NOW));
            assertThat(team.getId()).isNotNull();
            cursor.startFilling(team);
            members.save(team.addMember("alice", NOW));
            cursor.afterMemberJoined(team);
        });

        Long teamId = members.findTeamIdByCampaignIdAndUserId(campaign.getId(), "alice").orElseThrow();
        Team stored = teams.findById(teamId).orElseThrow();
        assertThat(stored.getMemberCount()).isEqualTo((short) 1);
        assertThat(stored.getStateSeq()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT filling_team_id FROM team_assignment_cursor WHERE campaign_id = ?",
                Long.class, campaign.getId())).isEqualTo(teamId);
    }

    @Test
    void domainChangesToTeamAndMemberAreWrittenTogetherAtCommit() {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        addMember(team, "alice");
        addMember(team, "bob");

        applyPoints(team, "alice", 120);
        applyPoints(team, "bob", 500);

        Team stored = teams.findById(team.getId()).orElseThrow();
        Integer memberPointSum = jdbc.queryForObject(
                "SELECT SUM(points) FROM team_member WHERE team_id = ?", Integer.class, team.getId());
        assertThat(stored.getTotalPoints()).isEqualTo(300).isEqualTo(memberPointSum);
        assertThat(stored.isCompleted()).isTrue();
        assertThat(stored.getCompletedAt()).isEqualTo(NOW);
        assertThat(stored.getStateSeq()).isEqualTo(4);
    }

    @Test
    void secondProgressWaitsForTheTeamLockAndBuildsOnTheCommittedPoints() throws Exception {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        addMember(team, "alice");
        addMember(team, "bob");
        applyPoints(team, "alice", 290);
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<ProgressOutcome> first = inTransactionAsync(() -> {
            Team locked = teams.findByIdForUpdate(team.getId()).orElseThrow();
            TeamMember alice = members.findByCampaignIdAndUserId(campaign.getId(), "alice").orElseThrow();
            ProgressOutcome outcome = locked.applyPoints(alice, 6, NOW);
            firstApplied.countDown();
            await(releaseFirst);
            return outcome;
        });
        await(firstApplied);
        Future<ProgressOutcome> second = inTransactionAsync(() -> {
            Team locked = teams.findByIdForUpdate(team.getId()).orElseThrow();
            TeamMember bob = members.findByCampaignIdAndUserId(campaign.getId(), "bob").orElseThrow();
            return locked.applyPoints(bob, 5, NOW);
        });

        awaitBlockedStatements(TEAM_LOCK, 1);
        assertThat(second).isNotDone();
        releaseFirst.countDown();

        assertThat(result(first)).isEqualTo(new ProgressOutcome.Applied(6, 6, 296, 296, 300, false));
        assertThat(result(second)).isEqualTo(new ProgressOutcome.Applied(5, 4, 4, 300, 300, true));
        assertThat(teams.findById(team.getId()).orElseThrow().getTotalPoints()).isEqualTo(300);
    }

    @Test
    void completionCheckDoesNotWaitForTheTeamLock() throws Exception {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        addMember(team, "alice");
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<Void> holder = inTransactionAsync(() -> {
            teams.findByIdForUpdate(team.getId()).orElseThrow();
            firstHoldsLock.countDown();
            await(releaseFirst);
            return null;
        });
        await(firstHoldsLock);

        try {
            assertThat(result(inTransactionAsync(() -> teams.isCompleted(team.getId())))).isFalse();
        } finally {
            releaseFirst.countDown();
            result(holder);
        }

        applyPoints(team, "alice", 300);
        assertThat(teams.isCompleted(team.getId())).isTrue();
    }

    @Test
    void rankingOrdersByPointsThenByWhoReachedThemFirst() {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        for (String user : List.of("erin", "frank", "dave", "gary")) {
            addMember(team, user);
        }
        applyPoints(team, "frank", 5);
        applyPoints(team, "dave", 5);
        applyPoints(team, "erin", 7);

        List<TeamRankingRow> ranking = teams.findRanking(campaign.getId(), team.getId());

        assertThat(ranking).extracting(TeamRankingRow::userId).containsExactly("erin", "frank", "dave", "gary");
        assertThat(ranking).extracting(TeamRankingRow::points).containsExactly(7, 5, 5, 0);
        assertThat(ranking).allSatisfy(row -> {
            assertThat(row.teamId()).isEqualTo(team.getId());
            assertThat(row.campaignId()).isEqualTo(campaign.getId());
            assertThat(row.memberCount()).isEqualTo((short) 4);
            assertThat(row.totalPoints()).isEqualTo(17);
            assertThat(row.targetPoints()).isEqualTo(300);
            assertThat(row.stateSeq()).isEqualTo(7);
            assertThat(row.completedAt()).isNull();
            assertThat(row.claimedAt()).isNull();
        });
    }

    @Test
    void rankingContainsOnlyTheRequestedTeam() {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        Team otherTeam = createTeam(campaign);
        addMember(team, "alice");
        addMember(otherTeam, "bob");

        assertThat(teams.findRanking(campaign.getId(), team.getId()))
                .extracting(TeamRankingRow::userId)
                .containsExactly("alice");
        assertThat(teams.findRanking(campaign.getId() + 1_000, team.getId())).isEmpty();
    }

    @Test
    void lockingReadOutsideATransactionIsRejected() {
        Team team = createTeam(createCampaign());

        assertThatThrownBy(() -> teams.findByIdForUpdate(team.getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
