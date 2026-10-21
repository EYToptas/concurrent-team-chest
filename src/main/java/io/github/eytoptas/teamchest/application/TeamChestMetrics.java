package io.github.eytoptas.teamchest.application;

import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import io.github.eytoptas.teamchest.application.join.JoinResult;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TeamChestMetrics {

    private final MeterRegistry meters;
    private final Counter pointsApplied;
    private final Counter pointsClipped;
    private final Counter teamsCompleted;

    public TeamChestMetrics(MeterRegistry meters) {
        this.meters = meters;
        this.pointsApplied = Counter.builder("teamchest.progress.points")
                .description("Points added to teams by applied /progress requests")
                .register(meters);
        this.pointsClipped = Counter.builder("teamchest.progress.clipped")
                .description("Applied /progress requests that were clipped at the team target")
                .register(meters);
        this.teamsCompleted = Counter.builder("teamchest.teams.completed")
                .description("Teams that reached their target")
                .register(meters);
    }

    public void joined(JoinResult result) {
        count("teamchest.joins", "Answered /join requests", "result", result.newlyJoined() ? "new" : "existing");
    }

    public void executed(Object outcome) {
        switch (outcome) {
            case ProgressResult.Applied applied -> {
                count("teamchest.progress", "Executed /progress requests", "outcome", "applied");
                pointsApplied.increment(applied.appliedPoints());
                if (applied.appliedPoints() < applied.requestedPoints()) {
                    pointsClipped.increment();
                }
                if (applied.teamCompleted()) {
                    teamsCompleted.increment();
                }
            }
            case ProgressResult.TeamAlreadyCompleted ignored -> progress("team_completed");
            case ProgressResult.NotAMember ignored -> progress("not_a_member");
            case ClaimResult.Claimed ignored -> claim("claimed");
            case ClaimResult.ChestNotReady ignored -> claim("chest_not_ready");
            case ClaimResult.AlreadyClaimed ignored -> claim("already_claimed");
            case ClaimResult.NotAMember ignored -> claim("not_a_member");
            default -> {
            }
        }
    }

    public void replayed(IdempotentOperation operation) {
        count("teamchest.idempotency.replays", "Requests answered from a stored outcome",
                "operation", operation.name().toLowerCase(Locale.ROOT));
    }

    public void rateLimited(RateLimitedOperation operation) {
        count("teamchest.ratelimit.rejected", "Requests rejected by the rate limiter", "operation", operation.key());
    }

    public void cacheLookup(String cache, boolean hit) {
        count("teamchest.cache", "Cache lookups of /get", "cache", cache, "result", hit ? "hit" : "miss");
    }

    private void progress(String outcome) {
        count("teamchest.progress", "Executed /progress requests", "outcome", outcome);
    }

    private void claim(String outcome) {
        count("teamchest.claims", "Executed /claim requests", "outcome", outcome);
    }

    private void count(String name, String description, String... tags) {
        Counter.builder(name).description(description).tags(tags).register(meters).increment();
    }
}
