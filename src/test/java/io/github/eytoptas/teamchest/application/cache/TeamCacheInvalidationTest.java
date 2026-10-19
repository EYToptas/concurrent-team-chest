package io.github.eytoptas.teamchest.application.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.eytoptas.teamchest.application.query.TeamOverview;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamCacheInvalidationTest {

    @Test
    void invalidatesTheChangedTeamWithTheCommittedVersion() {
        RecordingCache cache = new RecordingCache();

        new TeamCacheInvalidation(cache).onTeamStateChanged(new TeamStateChanged(7, 42, 13));

        assertThat(cache.invalidations).containsExactly(List.of(7L, 42L, 13L));
    }

    @Test
    void aFailingCacheDoesNotFailTheCommittedRequest() {
        RecordingCache cache = new RecordingCache();
        cache.failure = new IllegalStateException("cache bug");

        assertThatCode(() -> new TeamCacheInvalidation(cache).onTeamStateChanged(new TeamStateChanged(7, 42, 13)))
                .doesNotThrowAnyException();
    }

    private static final class RecordingCache implements TeamStandingCache {

        private final List<List<Long>> invalidations = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public Optional<TeamOverview.TeamStanding> find(long campaignId, long teamId) {
            return Optional.empty();
        }

        @Override
        public void offer(long campaignId, TeamOverview.TeamStanding standing) {
        }

        @Override
        public void invalidate(long campaignId, long teamId, long committedVersion) {
            if (failure != null) {
                throw failure;
            }
            invalidations.add(List.of(campaignId, teamId, committedVersion));
        }
    }
}
