package io.github.eytoptas.teamchest.application.query;

import io.github.eytoptas.teamchest.application.Commands;
import io.github.eytoptas.teamchest.application.campaign.CampaignCatalog;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import io.github.eytoptas.teamchest.persistence.team.TeamMemberRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamRankingRow;
import io.github.eytoptas.teamchest.persistence.team.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class TeamQueryService {

    private final CampaignCatalog campaignCatalog;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final Clock clock;

    public TeamQueryService(CampaignCatalog campaignCatalog, TeamRepository teams, TeamMemberRepository members,
                            Clock clock) {
        this.campaignCatalog = campaignCatalog;
        this.teams = teams;
        this.members = members;
        this.clock = clock;
    }

    public TeamOverview overviewFor(String userId) {
        Commands.requireUserId(userId);
        Instant now = clock.instant();
        Campaign campaign = campaignCatalog.requireCurrentCampaign(CampaignPhase::isOpen, now);
        CampaignPhase phase = campaignCatalog.phaseOf(campaign, now);
        TeamOverview.CampaignSummary summary = new TeamOverview.CampaignSummary(campaign.getId(), phase,
                campaign.getStartsAt(), campaign.getEndsAt(), campaignCatalog.claimDeadline(campaign),
                campaign.getTeamCapacity(), campaign.getTargetPoints());

        Optional<Long> teamId = members.findTeamIdByCampaignIdAndUserId(campaign.getId(), userId);
        if (teamId.isEmpty()) {
            return TeamOverview.notJoined(summary);
        }

        List<TeamRankingRow> rows = teams.findRanking(campaign.getId(), teamId.get());
        if (rows.isEmpty()) {
            throw new IllegalStateException("Team " + teamId.get() + " of a member has no members");
        }
        TeamOverview.TeamStanding team = standingOf(rows);
        TeamOverview.MemberStanding me = team.members().stream()
                .filter(member -> member.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("User " + userId + " missing from own team"));
        boolean canClaim = phase.allowsClaim() && team.completed() && !me.claimed();
        return new TeamOverview(summary, team, me, canClaim);
    }

    private static TeamOverview.TeamStanding standingOf(List<TeamRankingRow> rows) {
        List<TeamOverview.MemberStanding> standings = new ArrayList<>(rows.size());
        for (TeamRankingRow row : rows) {
            standings.add(new TeamOverview.MemberStanding(
                    standings.size() + 1, row.userId(), row.points(), row.claimedAt() != null));
        }
        TeamRankingRow team = rows.get(0);
        return new TeamOverview.TeamStanding(team.teamId(), team.memberCount(), team.totalPoints(),
                team.targetPoints(), team.completedAt() != null, team.stateSeq(), standings);
    }
}
