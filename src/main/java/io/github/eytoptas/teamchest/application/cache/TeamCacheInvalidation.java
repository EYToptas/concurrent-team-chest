package io.github.eytoptas.teamchest.application.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TeamCacheInvalidation {

    private static final Logger log = LoggerFactory.getLogger(TeamCacheInvalidation.class);

    private final TeamStandingCache teamStandingCache;

    public TeamCacheInvalidation(TeamStandingCache teamStandingCache) {
        this.teamStandingCache = teamStandingCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamStateChanged(TeamStateChanged change) {
        try {
            teamStandingCache.invalidate(change.campaignId(), change.teamId(), change.version());
        } catch (RuntimeException e) {
            log.warn("Could not invalidate the cached view of team {} (version {}); it expires with its TTL",
                    change.teamId(), change.version(), e);
        }
    }
}
