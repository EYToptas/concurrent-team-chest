package io.github.eytoptas.teamchest.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eytoptas.teamchest.application.ServiceIntegrationTest;
import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import io.github.eytoptas.teamchest.application.progress.ProgressCommand;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TeamStandingTests extends ServiceIntegrationTest {

    @Autowired
    private TeamQueryService queryService;

    @Test
    void equalPointsRankByWhoReachedThemFirst() {
        Campaign campaign = activeCampaign();
        for (String user : List.of("erin", "frank", "dave", "gary", "tracey")) {
            joinService.join(user);
        }
        progress(campaign, "dave", 3);
        progress(campaign, "frank", 5);
        progress(campaign, "dave", 2);
        progress(campaign, "erin", 1);

        TeamOverview overview = queryService.overviewFor("gary");

        assertThat(overview.team().members()).extracting(TeamOverview.MemberStanding::userId)
                .containsExactly("frank", "dave", "erin", "gary", "tracey");
        assertThat(overview.team().members()).extracting(TeamOverview.MemberStanding::points)
                .containsExactly(5, 5, 1, 0, 0);
        assertThat(overview.team().members()).extracting(TeamOverview.MemberStanding::rank)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(overview.me().rank()).isEqualTo(4);
        assertThat(overview.team().teamPoints()).isEqualTo(11);
    }

    @Test
    void rankingStaysExactThroughConcurrentProgressRetriesCompletionAndClaims() throws Exception {
        Campaign campaign = activeCampaign();
        List<String> users = IntStream.rangeClosed(1, 30).mapToObj(i -> "trainer-" + i).toList();
        long teamId = joinService.join(users.get(0)).teamId();
        for (String user : users.subList(1, users.size())) {
            assertThat(joinService.join(user).teamId()).isEqualTo(teamId);
        }
        List<ProgressCommand> commands = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (String user : users) {
                commands.add(new ProgressCommand(user, campaign.getId(), 7, newKey()));
            }
        }

        List<ProgressResult> answers = progressAllAtOnce(commands);
        List<ProgressResult> retried = progressAllAtOnce(commands);

        assertThat(retried).as("every retry answers with the stored outcome").isEqualTo(answers);
        List<ProgressResult.Applied> applied = answers.stream()
                .filter(ProgressResult.Applied.class::isInstance).map(ProgressResult.Applied.class::cast).toList();
        assertThat(applied.stream().mapToInt(ProgressResult.Applied::appliedPoints).sum()).isEqualTo(300);
        assertThat(applied).filteredOn(ProgressResult.Applied::teamCompleted).singleElement()
                .satisfies(completing -> assertThat(completing.appliedPoints()).isEqualTo(6));
        assertThat(applied).filteredOn(result -> result.appliedPoints() < result.requestedPoints()).hasSize(1);
        assertThat(answers).filteredOn(ProgressResult.TeamAlreadyCompleted.class::isInstance)
                .hasSize(answers.size() - applied.size());
        assertThat(applied).extracting(ProgressResult.Applied::version).doesNotHaveDuplicates();
        assertThat(teamPoints(teamId)).isEqualTo(300).isEqualTo(memberPointSum(teamId));
        assertThat(progressRecords(campaign)).as("one stored outcome per key").isEqualTo(commands.size());

        TeamOverview.TeamStanding completed = queryService.overviewFor(users.get(0)).team();

        assertThat(completed.completed()).isTrue();
        assertThat(completed.teamPoints()).isEqualTo(300);
        assertThat(completed.memberCount()).isEqualTo(30);
        assertThat(completed.members()).extracting(TeamOverview.MemberStanding::userId)
                .containsExactlyElementsOf(rankingByMySql(teamId));
        assertThat(completed.members()).extracting(TeamOverview.MemberStanding::rank)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 30).boxed().toList());
        Map<String, Integer> storedPoints = pointsByUser(teamId);
        assertThat(completed.members()).allSatisfy(member ->
                assertThat(member.points()).isEqualTo(storedPoints.get(member.userId())));
        assertThat(completed.members().stream().map(TeamOverview.MemberStanding::points).distinct().count())
                .as("members tie at the same point count, so the tie-break decides their order")
                .isLessThan(30);

        for (String user : users) {
            assertThat(claim(campaign, user)).isInstanceOf(ClaimResult.Claimed.class);
        }
        for (String user : users) {
            assertThat(claim(campaign, user)).isInstanceOf(ClaimResult.AlreadyClaimed.class);
        }
        TeamOverview afterClaims = queryService.overviewFor(users.get(0));

        assertThat(afterClaims.team().members()).extracting(TeamOverview.MemberStanding::userId)
                .as("claims change state_seq, not who reached which count first")
                .containsExactlyElementsOf(completed.members().stream().map(TeamOverview.MemberStanding::userId).toList());
        assertThat(afterClaims.team().members()).allSatisfy(member -> assertThat(member.claimed()).isTrue());
        assertThat(afterClaims.team().version()).isEqualTo(completed.version() + 30);
        assertThat(afterClaims.canClaim()).isFalse();
        assertThat(teamPoints(teamId)).isEqualTo(300).isEqualTo(memberPointSum(teamId));
    }

    private List<ProgressResult> progressAllAtOnce(List<ProgressCommand> commands) throws Exception {
        List<Future<ProgressResult>> requests = new ArrayList<>();
        for (ProgressCommand command : commands) {
            requests.add(async(() -> progressService.progress(command)));
        }
        List<ProgressResult> results = new ArrayList<>();
        for (Future<ProgressResult> request : requests) {
            results.add(result(request));
        }
        return results;
    }

    private List<String> rankingByMySql(long teamId) {
        return jdbc.queryForList(
                "SELECT user_id FROM team_member WHERE team_id = ? ORDER BY points DESC, points_reached_seq ASC",
                String.class, teamId);
    }

    private Map<String, Integer> pointsByUser(long teamId) {
        return jdbc.queryForList("SELECT user_id, points FROM team_member WHERE team_id = ?", teamId).stream()
                .collect(Collectors.toMap(row -> (String) row.get("user_id"), row -> ((Number) row.get("points")).intValue()));
    }

    private int progressRecords(Campaign campaign) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE campaign_id = ? AND operation = ?",
                Integer.class, campaign.getId(), IdempotentOperation.PROGRESS.name());
    }
}
