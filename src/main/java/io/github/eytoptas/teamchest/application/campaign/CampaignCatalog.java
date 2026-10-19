package io.github.eytoptas.teamchest.application.campaign;

import io.github.eytoptas.teamchest.application.TeamChestProperties;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import io.github.eytoptas.teamchest.persistence.campaign.CampaignRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class CampaignCatalog {

    private final CampaignRepository campaigns;
    private final Duration claimGracePeriod;
    private final Map<Long, Campaign> campaignsById = new ConcurrentHashMap<>();

    public CampaignCatalog(CampaignRepository campaigns, TeamChestProperties properties) {
        this.campaigns = campaigns;
        this.claimGracePeriod = properties.campaign().claimGracePeriod();
    }

    public Campaign requireCampaign(long campaignId, Predicate<CampaignPhase> operationAllowed, Instant now) {
        Campaign campaign = findById(campaignId);
        CampaignPhase phase = campaign.phaseAt(now, claimGracePeriod);
        if (!operationAllowed.test(phase)) {
            throw new CampaignNotActiveException(campaignId, phase);
        }
        return campaign;
    }

    public Campaign requireCurrentCampaign(Predicate<CampaignPhase> operationAllowed, Instant now) {
        List<Campaign> open = campaigns.findByEndsAtAfter(now.minus(claimGracePeriod)).stream()
                .filter(campaign -> isOpen(campaign.phaseAt(now, claimGracePeriod)))
                .toList();
        if (open.size() > 1) {
            throw new IllegalStateException("Overlapping campaigns are not supported: "
                    + open.stream().map(Campaign::getCode).toList());
        }
        Campaign current = open.stream()
                .filter(campaign -> operationAllowed.test(campaign.phaseAt(now, claimGracePeriod)))
                .findFirst()
                .orElseThrow(NoActiveCampaignException::new);
        campaignsById.putIfAbsent(current.getId(), current);
        return current;
    }

    private Campaign findById(long campaignId) {
        Campaign cached = campaignsById.get(campaignId);
        if (cached != null) {
            return cached;
        }
        Campaign loaded = campaigns.findById(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
        campaignsById.putIfAbsent(campaignId, loaded);
        return loaded;
    }

    private static boolean isOpen(CampaignPhase phase) {
        return phase != CampaignPhase.NOT_STARTED && phase != CampaignPhase.CLOSED;
    }
}
