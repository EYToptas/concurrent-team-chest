package io.github.eytoptas.teamchest.domain;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

public final class DomainFixtures {

    public static final Instant T0 = Instant.parse("2026-10-18T12:00:00Z");
    public static final Instant CAMPAIGN_START = Instant.parse("2026-10-01T00:00:00Z");
    public static final Instant CAMPAIGN_END = Instant.parse("2026-10-31T00:00:00Z");

    private DomainFixtures() {
    }

    public static Campaign persistedCampaign(long id, int teamCapacity, int targetPoints) {
        Campaign campaign = new Campaign("campaign-" + id, teamCapacity, targetPoints, "{\"coins\": 500}",
                CAMPAIGN_START, CAMPAIGN_END);
        return withId(campaign, id);
    }

    public static Campaign persistedCampaign(long id) {
        return persistedCampaign(id, 30, 300);
    }

    public static Team persistedTeam(Campaign campaign, long id) {
        return withId(Team.createFor(campaign, T0), id);
    }

    static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
