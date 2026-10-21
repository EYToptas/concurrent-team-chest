package io.github.eytoptas.teamchest.domain.team;

import static io.github.eytoptas.teamchest.domain.DomainFixtures.T0;
import static io.github.eytoptas.teamchest.domain.DomainFixtures.persistedCampaign;
import static io.github.eytoptas.teamchest.domain.DomainFixtures.persistedTeam;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MemberRankingTest {

    private record Ranked(String userId, int points, long pointsReachedSeq) {
    }

    private static final Comparator<Ranked> ORDER = MemberRanking.order(Ranked::points, Ranked::pointsReachedSeq);

    @Test
    void morePointsRankHigher() {
        List<Ranked> members = new ArrayList<>(List.of(
                new Ranked("gary", 0, 1), new Ranked("dave", 5, 2), new Ranked("erin", 7, 3)));

        members.sort(ORDER);

        assertThat(members).extracting(Ranked::userId).containsExactly("erin", "dave", "gary");
    }

    @Test
    void equalPointsRankByWhoReachedThemFirst() {
        List<Ranked> members = new ArrayList<>(List.of(
                new Ranked("dave", 5, 9), new Ranked("frank", 5, 4), new Ranked("erin", 5, 6)));

        members.sort(ORDER);

        assertThat(members).extracting(Ranked::userId).containsExactly("frank", "erin", "dave");
    }

    @Test
    void orderDoesNotDependOnTheInputOrder() {
        List<Ranked> expected = List.of(
                new Ranked("erin", 14, 40), new Ranked("frank", 7, 12), new Ranked("dave", 7, 31),
                new Ranked("tracey", 6, 44), new Ranked("gary", 0, 2), new Ranked("may", 0, 3));
        Random random = new Random(42);

        for (int i = 0; i < 100; i++) {
            List<Ranked> shuffled = new ArrayList<>(expected);
            Collections.shuffle(shuffled, random);
            shuffled.sort(ORDER);
            assertThat(shuffled).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void reachingTheSameCountLaterRanksLowerAndMembersWithoutPointsRankByJoinOrder() {
        Team team = persistedTeam(persistedCampaign(1), 10);
        TeamMember erin = team.addMember("erin", T0);
        TeamMember frank = team.addMember("frank", T0);
        TeamMember dave = team.addMember("dave", T0);
        TeamMember gary = team.addMember("gary", T0);
        team.applyPoints(erin, 2, T0);
        team.applyPoints(frank, 5, T0);
        team.applyPoints(erin, 3, T0);

        List<TeamMember> members = new ArrayList<>(List.of(gary, dave, erin, frank));
        members.sort(MemberRanking.order(TeamMember::getPoints, TeamMember::getPointsReachedSeq));

        assertThat(members).extracting(TeamMember::getUserId).containsExactly("frank", "erin", "dave", "gary");
        assertThat(members).extracting(TeamMember::getPoints).containsExactly(5, 5, 0, 0);
    }
}
