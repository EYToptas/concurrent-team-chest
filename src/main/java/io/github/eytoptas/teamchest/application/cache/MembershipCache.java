package io.github.eytoptas.teamchest.application.cache;

import java.util.Optional;

public interface MembershipCache {

    Optional<Long> findTeamId(long campaignId, String userId);

    void remember(long campaignId, String userId, long teamId);
}
