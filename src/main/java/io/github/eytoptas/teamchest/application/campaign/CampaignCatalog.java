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
    private final Duration openCampaignsRefresh;
    private final Map<Long, Campaign> campaignsById = new ConcurrentHashMap<>();
    private volatile OpenCampaigns openCampaigns;

    public CampaignCatalog(CampaignRepository campaigns, TeamChestProperties properties) {
        this.campaigns = campaigns;
        this.claimGracePeriod = properties.campaign().claimGracePeriod();
        this.openCampaignsRefresh = properties.campaign().openCampaignsRefresh();
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
        List<Campaign> open = notClosedAt(now).stream()
                .filter(campaign -> campaign.phaseAt(now, claimGracePeriod).isOpen())
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

    public CampaignPhase phaseOf(Campaign campaign, Instant now) {
        return campaign.phaseAt(now, claimGracePeriod);
    }

    public Instant claimDeadline(Campaign campaign) {
        return campaign.claimWindowEnd(claimGracePeriod);
    }

    private List<Campaign> notClosedAt(Instant now) {
        OpenCampaigns loaded = openCampaigns;
        if (loaded != null && loaded.covers(now, openCampaignsRefresh)) {
            return loaded.campaigns();
        }
        loaded = new OpenCampaigns(List.copyOf(campaigns.findByEndsAtAfter(now.minus(claimGracePeriod))), now);
        if (!openCampaignsRefresh.isZero()) {
            openCampaigns = loaded;
        }
        return loaded.campaigns();
    }

    private record OpenCampaigns(List<Campaign> campaigns, Instant loadedAt) {

        boolean covers(Instant now, Duration refresh) {
            return !now.isBefore(loadedAt) && now.isBefore(loadedAt.plus(refresh));
        }
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
}
