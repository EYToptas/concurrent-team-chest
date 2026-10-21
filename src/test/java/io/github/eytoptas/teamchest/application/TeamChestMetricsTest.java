package io.github.eytoptas.teamchest.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import io.github.eytoptas.teamchest.application.join.JoinResult;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TeamChestMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TeamChestMetrics metrics = new TeamChestMetrics(meters);

    @Test
    void appliedProgressCountsPointsClippingAndCompletion() {
        metrics.executed(new ProgressResult.Applied(1, 7, 6, 6, 6, 296, 300, false, 5));
        metrics.executed(new ProgressResult.Applied(1, 7, 5, 4, 4, 300, 300, true, 6));

        assertThat(count("teamchest.progress", "outcome", "applied")).isEqualTo(2);
        assertThat(meters.get("teamchest.progress.points").counter().count()).isEqualTo(10);
        assertThat(meters.get("teamchest.progress.clipped").counter().count()).isEqualTo(1);
        assertThat(meters.get("teamchest.teams.completed").counter().count()).isEqualTo(1);
    }

    @Test
    void rejectionsAreCountedByOutcome() {
        metrics.executed(new ProgressResult.TeamAlreadyCompleted(1, 7, 300));
        metrics.executed(new ProgressResult.NotAMember(1));
        metrics.executed(new ClaimResult.ChestNotReady(1, 7, 290, 300));
        metrics.executed(new ClaimResult.AlreadyClaimed(1, 7, Instant.EPOCH));

        assertThat(count("teamchest.progress", "outcome", "team_completed")).isEqualTo(1);
        assertThat(count("teamchest.progress", "outcome", "not_a_member")).isEqualTo(1);
        assertThat(count("teamchest.claims", "outcome", "chest_not_ready")).isEqualTo(1);
        assertThat(count("teamchest.claims", "outcome", "already_claimed")).isEqualTo(1);
    }

    @Test
    void claimsJoinsReplaysRateLimitsAndCacheLookupsAreCounted() {
        metrics.executed(new ClaimResult.Claimed(1, 7, "{\"coins\":500}", Instant.EPOCH, 8));
        metrics.joined(new JoinResult(1, 7, true));
        metrics.joined(new JoinResult(1, 7, false));
        metrics.replayed(IdempotentOperation.PROGRESS);
        metrics.rateLimited(RateLimitedOperation.GET);
        metrics.cacheLookup("team", true);
        metrics.cacheLookup("team", false);

        assertThat(count("teamchest.claims", "outcome", "claimed")).isEqualTo(1);
        assertThat(count("teamchest.joins", "result", "new")).isEqualTo(1);
        assertThat(count("teamchest.joins", "result", "existing")).isEqualTo(1);
        assertThat(count("teamchest.idempotency.replays", "operation", "progress")).isEqualTo(1);
        assertThat(count("teamchest.ratelimit.rejected", "operation", "get")).isEqualTo(1);
        assertThat(meters.get("teamchest.cache").tags("cache", "team", "result", "hit").counter().count()).isEqualTo(1);
        assertThat(meters.get("teamchest.cache").tags("cache", "team", "result", "miss").counter().count()).isEqualTo(1);
    }

    private double count(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).counter().count();
    }
}
