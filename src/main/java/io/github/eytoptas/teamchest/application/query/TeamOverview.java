package io.github.eytoptas.teamchest.application.query;

import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record TeamOverview(CampaignSummary campaign, TeamStanding team, MemberStanding me, boolean canClaim) {

    public static TeamOverview notJoined(CampaignSummary campaign) {
        return new TeamOverview(campaign, null, null, false);
    }

    public record CampaignSummary(long campaignId, CampaignPhase phase, Instant startsAt, Instant endsAt,
                                  Instant claimableUntil, int teamCapacity, int targetPoints) {
    }

    public record TeamStanding(long teamId, int memberCount, int teamPoints, int targetPoints, boolean completed,
                               long version, List<MemberStanding> members) {

        public TeamStanding {
            members = List.copyOf(members);
        }

        public Optional<MemberStanding> member(String userId) {
            return members.stream().filter(member -> member.userId().equals(userId)).findFirst();
        }
    }

    public record MemberStanding(int rank, String userId, int points, boolean claimed) {
    }
}
