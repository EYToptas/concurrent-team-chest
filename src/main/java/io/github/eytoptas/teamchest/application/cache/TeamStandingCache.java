package io.github.eytoptas.teamchest.application.cache;

import io.github.eytoptas.teamchest.application.query.TeamOverview;
import java.util.Optional;

public interface TeamStandingCache {

    Optional<TeamOverview.TeamStanding> find(long campaignId, long teamId);

    void offer(long campaignId, TeamOverview.TeamStanding standing);

    void invalidate(long campaignId, long teamId, long committedVersion);
}
