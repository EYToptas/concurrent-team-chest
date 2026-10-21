package io.github.eytoptas.teamchest.persistence.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.MemberRanking;
import io.github.eytoptas.teamchest.domain.team.ProgressOutcome;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamAssignmentCursor;
import io.github.eytoptas.teamchest.domain.team.TeamMember;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import io.github.eytoptas.teamchest.persistence.RecordedSql;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    void teamOfAMemberIsFoundByCampaignAndUser() {
        Campaign campaign = createCampaign();
        Campaign otherCampaign = createCampaign();
        Team team = createTeam(campaign);
        Team otherTeam = createTeam(otherCampaign);
        addMember(team, "alice");
        addMember(otherTeam, "carol");

        assertThat(inTransaction(() -> teams.findTeamOfMemberForUpdate(campaign.getId(), "alice")))
                .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(team.getId()));
        assertThat(inTransaction(() -> teams.findTeamOfMemberForUpdate(otherCampaign.getId(), "alice"))).isEmpty();
        assertThat(inTransaction(() -> teams.findTeamOfMemberForUpdate(campaign.getId(), "Alice"))).isEmpty();
        assertThat(inTransaction(() -> teams.findTeamOfMemberForUpdate(campaign.getId(), "carol"))).isEmpty();
        assertThatThrownBy(() -> teams.findTeamOfMemberForUpdate(campaign.getId(), "alice"))
                .as("only inside a transaction, which holds the lock until it ends")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void teamOfAMemberIsLockedButNotItsMembership() throws Exception {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        addMember(team, "alice");
        CountDownLatch holdsLock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> holder = inTransactionAsync(() -> {
            teams.findTeamOfMemberForUpdate(campaign.getId(), "alice").orElseThrow();
            holdsLock.countDown();
            await(release);
            return null;
        });
        await(holdsLock);

        try {
            assertThat(result(async(() -> jdbc.update(
                    "UPDATE team_member SET joined_at = joined_at WHERE campaign_id = ? AND user_id = 'alice'",
                    campaign.getId())))).isEqualTo(1);
            Future<Team> waiter = inTransactionAsync(() -> teams.findByIdForUpdate(team.getId()).orElseThrow());
            awaitBlockedStatements(TEAM_LOCK, 1);
            assertThat(waiter).isNotDone();
            release.countDown();
            assertThat(result(waiter).getId()).isEqualTo(team.getId());
        } finally {
            release.countDown();
            result(holder);
        }
    }

    @Test
    void teamIsReadWithAllItsMembersAndTheKeysOfTheRanking() {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        for (String user : List.of("erin", "frank", "dave", "gary")) {
            addMember(team, user);
        }
        applyPoints(team, "frank", 5);
        applyPoints(team, "dave", 5);
        applyPoints(team, "erin", 7);

        List<TeamRankingRow> rows = teams.findTeamWithMembers(campaign.getId(), team.getId());

        assertThat(rows).extracting(TeamRankingRow::userId).containsExactlyInAnyOrder("erin", "frank", "dave", "gary");
        Map<String, TeamRankingRow> byUser = rows.stream()
                .collect(Collectors.toMap(TeamRankingRow::userId, Function.identity()));
        assertThat(byUser.get("erin").points()).isEqualTo(7);
        assertThat(byUser.get("erin").pointsReachedSeq()).isEqualTo(7);
        assertThat(byUser.get("frank").points()).isEqualTo(5);
        assertThat(byUser.get("frank").pointsReachedSeq()).isEqualTo(5);
        assertThat(byUser.get("dave").points()).isEqualTo(5);
        assertThat(byUser.get("dave").pointsReachedSeq()).isEqualTo(6);
        assertThat(byUser.get("gary").points()).isZero();
        assertThat(byUser.get("gary").pointsReachedSeq()).isEqualTo(4);
        assertThat(rows.stream().sorted(MemberRanking.order(TeamRankingRow::points, TeamRankingRow::pointsReachedSeq))
                .map(TeamRankingRow::userId).toList())
                .as("the /get order").containsExactly("erin", "frank", "dave", "gary");
        assertThat(rows).allSatisfy(row -> {
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
    void teamWithMembersContainsOnlyTheRequestedTeam() {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        Team otherTeam = createTeam(campaign);
        addMember(team, "alice");
        addMember(otherTeam, "bob");

        assertThat(teams.findTeamWithMembers(campaign.getId(), team.getId()))
                .extracting(TeamRankingRow::userId)
                .containsExactly("alice");
        assertThat(teams.findTeamWithMembers(campaign.getId() + 1_000, team.getId())).isEmpty();
    }

    @Test
    void teamWithMembersIsReadThroughTheMemberTeamIndex() {
        Campaign campaign = createCampaign();
        List<Team> campaignTeams = List.of(createTeam(campaign), createTeam(campaign), createTeam(campaign));
        for (Team team : campaignTeams) {
            for (int i = 1; i <= 5; i++) {
                addMember(team, "trainer-" + team.getId() + "-" + i);
            }
        }
        jdbc.execute("ANALYZE TABLE team_member");
        Team team = campaignTeams.get(1);

        List<String> statements = RecordedSql.during(() -> teams.findTeamWithMembers(campaign.getId(), team.getId()));

        assertThat(statements).hasSize(1);
        String statement = statements.get(0);
        List<Long> parameters = Pattern.compile("(\\w+)\\s*=\\s*\\?").matcher(statement).results()
                .map(match -> match.group(1).equalsIgnoreCase("campaign_id") ? campaign.getId() : team.getId())
                .toList();
        assertThat(parameters).as("team id, campaign id, member team id, member campaign id").hasSize(4);
        assertThat(statement.chars().filter(c -> c == '?').count()).isEqualTo(4);
        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + statement, parameters.toArray());
        assertThat(plan).hasSize(2);
        assertThat(plan).extracting(row -> row.get("type")).doesNotContain("ALL", "index");
        assertThat(plan).anySatisfy(row -> {
            assertThat(row.get("key")).isEqualTo("ix_member_team");
            assertThat(row.get("type")).isEqualTo("ref");
        });
        assertThat(plan).extracting(row -> String.valueOf(row.get("Extra")))
                .noneMatch(extra -> extra.contains("filesort") || extra.contains("temporary"));
    }

    @Test
    void lockingReadOutsideATransactionIsRejected() {
        Team team = createTeam(createCampaign());

        assertThatThrownBy(() -> teams.findByIdForUpdate(team.getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
