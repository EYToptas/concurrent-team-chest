package io.github.eytoptas.teamchest.api.response;

import io.github.eytoptas.teamchest.application.query.TeamOverview;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import java.time.Instant;
import java.util.List;

public record TeamOverviewResponse(CampaignResponse campaign, TeamResponse team, MemberResponse me, boolean canClaim) {

    public static TeamOverviewResponse from(TeamOverview overview) {
        return new TeamOverviewResponse(
                CampaignResponse.from(overview.campaign()),
                overview.team() == null ? null : TeamResponse.from(overview.team()),
                overview.me() == null ? null : MemberResponse.from(overview.me()),
                overview.canClaim());
    }

    public record CampaignResponse(long campaignId, CampaignPhase phase, Instant startsAt, Instant endsAt,
                                   Instant claimableUntil, int teamCapacity, int targetPoints) {

        public static CampaignResponse from(TeamOverview.CampaignSummary campaign) {
            return new CampaignResponse(campaign.campaignId(), campaign.phase(), campaign.startsAt(), campaign.endsAt(),
                    campaign.claimableUntil(), campaign.teamCapacity(), campaign.targetPoints());
        }
    }

    public record TeamResponse(long teamId, int memberCount, int teamPoints, int targetPoints, boolean completed,
                               long version, List<MemberResponse> members) {

        static TeamResponse from(TeamOverview.TeamStanding team) {
            return new TeamResponse(team.teamId(), team.memberCount(), team.teamPoints(), team.targetPoints(),
                    team.completed(), team.version(), team.members().stream().map(MemberResponse::from).toList());
        }
    }

    public record MemberResponse(int rank, String userId, int points, boolean claimed) {

        static MemberResponse from(TeamOverview.MemberStanding member) {
            return new MemberResponse(member.rank(), member.userId(), member.points(), member.claimed());
        }
    }
}
