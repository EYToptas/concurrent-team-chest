package io.github.eytoptas.teamchest.application.query;

import io.github.eytoptas.teamchest.application.Commands;
import io.github.eytoptas.teamchest.application.cache.MembershipCache;
import io.github.eytoptas.teamchest.application.cache.TeamStandingCache;
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
    private final MembershipCache membershipCache;
    private final TeamStandingCache teamStandingCache;
    private final Clock clock;

    public TeamQueryService(CampaignCatalog campaignCatalog, TeamRepository teams, TeamMemberRepository members,
                            MembershipCache membershipCache, TeamStandingCache teamStandingCache, Clock clock) {
        this.campaignCatalog = campaignCatalog;
        this.teams = teams;
        this.members = members;
        this.membershipCache = membershipCache;
        this.teamStandingCache = teamStandingCache;
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

        Optional<Long> teamId = teamIdOf(campaign.getId(), userId);
        if (teamId.isEmpty()) {
            return TeamOverview.notJoined(summary);
        }

        TeamOverview.TeamStanding team = standingOf(campaign.getId(), teamId.get(), userId);
        TeamOverview.MemberStanding me = team.member(userId)
                .orElseThrow(() -> new IllegalStateException("User " + userId + " missing from own team"));
        boolean canClaim = phase.allowsClaim() && team.completed() && !me.claimed();
        return new TeamOverview(summary, team, me, canClaim);
    }

    private Optional<Long> teamIdOf(long campaignId, String userId) {
        Optional<Long> cached = membershipCache.findTeamId(campaignId, userId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Long> stored = members.findTeamIdByCampaignIdAndUserId(campaignId, userId);
        stored.ifPresent(teamId -> membershipCache.remember(campaignId, userId, teamId));
        return stored;
    }

    private TeamOverview.TeamStanding standingOf(long campaignId, long teamId, String userId) {
        return teamStandingCache.find(campaignId, teamId)
                .filter(cached -> cached.member(userId).isPresent())
                .orElseGet(() -> {
                    TeamOverview.TeamStanding loaded = loadStanding(campaignId, teamId);
                    teamStandingCache.offer(campaignId, loaded);
                    return loaded;
                });
    }

    private TeamOverview.TeamStanding loadStanding(long campaignId, long teamId) {
        List<TeamRankingRow> rows = teams.findRanking(campaignId, teamId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Team " + teamId + " of a member has no members");
        }
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
