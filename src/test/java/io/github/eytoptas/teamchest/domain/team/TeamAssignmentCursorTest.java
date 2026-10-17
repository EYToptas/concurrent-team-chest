package io.github.eytoptas.teamchest.domain.team;

import static io.github.eytoptas.teamchest.domain.DomainFixtures.T0;
import static io.github.eytoptas.teamchest.domain.DomainFixtures.persistedCampaign;
import static io.github.eytoptas.teamchest.domain.DomainFixtures.persistedTeam;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.domain.DomainFixtures;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TeamAssignmentCursorTest {

    private final Campaign campaign = persistedCampaign(1L);
    private final TeamAssignmentCursor cursor = TeamAssignmentCursor.forCampaign(campaign);

    @Test
    void newCursorHasNoFillingTeam() {
        assertThat(cursor.getCampaignId()).isEqualTo(1L);
        assertThat(cursor.fillingTeamId()).isEmpty();
    }

    @Test
    void startFillingPointsToTheTeam() {
        Team team = persistedTeam(campaign, 10L);

        cursor.startFilling(team);

        assertThat(cursor.fillingTeamId()).contains(10L);
    }

    @Test
    void refusesASecondFillingTeam() {
        cursor.startFilling(persistedTeam(campaign, 10L));

        assertThatThrownBy(() -> cursor.startFilling(persistedTeam(campaign, 11L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cursor.fillingTeamId()).contains(10L);
    }

    @Test
    void refusesAFullTeam() {
        Team pair = persistedTeam(persistedCampaign(2L, 2, 300), 20L);
        pair.addMember("a", T0);
        pair.addMember("b", T0);
        TeamAssignmentCursor pairCursor = TeamAssignmentCursor.forCampaign(persistedCampaign(2L, 2, 300));

        assertThatThrownBy(() -> pairCursor.startFilling(pair)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesATeamOfAnotherCampaign() {
        Team foreignTeam = persistedTeam(persistedCampaign(2L), 20L);

        assertThatThrownBy(() -> cursor.startFilling(foreignTeam)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnUnpersistedTeam() {
        assertThatThrownBy(() -> cursor.startFilling(Team.createFor(campaign, T0)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keepsTheFillingTeamUntilItIsFullThenReleasesIt() {
        Team team = persistedTeam(campaign, 10L);
        cursor.startFilling(team);

        for (int i = 1; i < 30; i++) {
            team.addMember("user-" + i, T0);
            cursor.afterMemberJoined(team);
            assertThat(cursor.fillingTeamId()).contains(10L);
        }

        team.addMember("user-30", T0);
        cursor.afterMemberJoined(team);

        assertThat(cursor.fillingTeamId()).isEmpty();
    }

    @Test
    void afterMemberJoinedRejectsATeamThatIsNotFilling() {
        cursor.startFilling(persistedTeam(campaign, 10L));

        assertThatThrownBy(() -> cursor.afterMemberJoined(persistedTeam(campaign, 11L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresAPersistedCampaign() {
        Campaign unsaved = new Campaign("unsaved", 30, 300, "{}", DomainFixtures.CAMPAIGN_START, DomainFixtures.CAMPAIGN_END);

        assertThatThrownBy(() -> TeamAssignmentCursor.forCampaign(unsaved)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "{0} players -> {1} full teams, last team {2}")
    @CsvSource({
            "1,   0, 1",
            "30,  1, 0",
            "31,  1, 1",
            "146, 4, 26",
            "150, 5, 0"
    })
    void joinsFillTeamsOneByOne(int players, int expectedFullTeams, int expectedFillingTeamSize) {
        List<Team> teams = new ArrayList<>();

        for (int i = 1; i <= players; i++) {
            Team filling = currentFillingTeam(teams);
            filling.addMember("player-" + i, T0);
            cursor.afterMemberJoined(filling);

            assertThat(teams.stream().filter(team -> !team.isFull()).count()).isLessThanOrEqualTo(1);
            assertThat(teams).allSatisfy(team -> assertThat(team.getMemberCount()).isLessThanOrEqualTo((short) 30));
        }

        assertThat(teams.stream().filter(Team::isFull).count()).isEqualTo(expectedFullTeams);
        if (expectedFillingTeamSize == 0) {
            assertThat(cursor.fillingTeamId()).isEmpty();
        } else {
            Team filling = teams.get(teams.size() - 1);
            assertThat(cursor.fillingTeamId()).contains(filling.getId());
            assertThat(filling.getMemberCount()).isEqualTo((short) expectedFillingTeamSize);
        }
    }

    private Team currentFillingTeam(List<Team> teams) {
        return cursor.fillingTeamId()
                .map(id -> teams.stream().filter(team -> team.getId().equals(id)).findFirst().orElseThrow())
                .orElseGet(() -> {
                    Team created = persistedTeam(campaign, 100L + teams.size());
                    teams.add(created);
                    cursor.startFilling(created);
                    return created;
                });
    }
}
