package io.github.eytoptas.teamchest.application.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.TeamChestProperties;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import io.github.eytoptas.teamchest.persistence.campaign.CampaignRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CampaignCatalogTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Duration GRACE = Duration.ofDays(7);

    private final InMemoryCampaignRepository repository = new InMemoryCampaignRepository();
    private final CampaignCatalog catalog = new CampaignCatalog(repository, new TeamChestProperties(
            new TeamChestProperties.CampaignSettings(GRACE),
            new TeamChestProperties.TransactionSettings(3, Duration.ZERO, Duration.ZERO)));

    @Test
    void returnsAnCampaignWhosePhaseAllowsTheOperation() {
        Campaign active = repository.add(1, NOW.minusSeconds(3600), NOW.plusSeconds(3600));

        assertThat(catalog.requireCampaign(1, CampaignPhase::allowsProgress, NOW)).isSameAs(active);
    }

    @Test
    void rejectsUnknownCampaigns() {
        assertThatThrownBy(() -> catalog.requireCampaign(42, CampaignPhase::allowsProgress, NOW))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    @Test
    void endedCampaignAllowsClaimingButNotProgress() {
        repository.add(1, NOW.minus(Duration.ofDays(2)), NOW.minusSeconds(60));

        assertThat(catalog.requireCampaign(1, CampaignPhase::allowsClaim, NOW)).isNotNull();
        assertThatThrownBy(() -> catalog.requireCampaign(1, CampaignPhase::allowsProgress, NOW))
                .isInstanceOfSatisfying(CampaignNotActiveException.class,
                        e -> assertThat(e.getPhase()).isEqualTo(CampaignPhase.CLAIM_ONLY));
    }

    @Test
    void closedCampaignAllowsNothing() {
        repository.add(1, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(10)));

        assertThatThrownBy(() -> catalog.requireCampaign(1, CampaignPhase::allowsClaim, NOW))
                .isInstanceOf(CampaignNotActiveException.class);
    }

    @Test
    void campaignsAreLoadedOnceAndThenServedFromMemory() {
        repository.add(1, NOW.minusSeconds(3600), NOW.plusSeconds(3600));

        catalog.requireCampaign(1, CampaignPhase::allowsProgress, NOW);
        catalog.requireCampaign(1, CampaignPhase::allowsProgress, NOW);

        assertThat(repository.findByIdCalls).isEqualTo(1);
    }

    @Test
    void currentCampaignIsTheOneWhoseWindowContainsNow() {
        repository.add(1, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(10)));
        Campaign active = repository.add(2, NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        repository.add(3, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(2)));

        assertThat(catalog.requireCurrentCampaign(CampaignPhase::allowsJoin, NOW)).isSameAs(active);
    }

    @Test
    void noCurrentCampaignOrAPhaseThatDoesNotAllowTheOperationIsRejected() {
        assertThatThrownBy(() -> catalog.requireCurrentCampaign(CampaignPhase::allowsJoin, NOW))
                .isInstanceOf(NoActiveCampaignException.class);

        repository.add(1, NOW.minus(Duration.ofDays(2)), NOW.minusSeconds(60));

        assertThatThrownBy(() -> catalog.requireCurrentCampaign(CampaignPhase::allowsJoin, NOW))
                .isInstanceOf(NoActiveCampaignException.class);
    }

    @Test
    void overlappingCampaignsAreAConfigurationError() {
        repository.add(1, NOW.minus(Duration.ofDays(2)), NOW.minusSeconds(60));
        repository.add(2, NOW.minusSeconds(30), NOW.plusSeconds(3600));

        assertThatThrownBy(() -> catalog.requireCurrentCampaign(CampaignPhase::allowsJoin, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final class InMemoryCampaignRepository implements CampaignRepository {

        private final List<Campaign> campaigns = new ArrayList<>();
        int findByIdCalls;

        Campaign add(long id, Instant startsAt, Instant endsAt) {
            Campaign campaign = new Campaign("campaign-" + id, 30, 300, "{}", startsAt, endsAt);
            ReflectionTestUtils.setField(campaign, "id", id);
            campaigns.add(campaign);
            return campaign;
        }

        @Override
        public Campaign save(Campaign campaign) {
            campaigns.add(campaign);
            return campaign;
        }

        @Override
        public Optional<Campaign> findById(Long id) {
            findByIdCalls++;
            return campaigns.stream().filter(campaign -> campaign.getId().equals(id)).findFirst();
        }

        @Override
        public List<Campaign> findByEndsAtAfter(Instant endsAfter) {
            return campaigns.stream().filter(campaign -> campaign.getEndsAt().isAfter(endsAfter)).toList();
        }
    }
}
