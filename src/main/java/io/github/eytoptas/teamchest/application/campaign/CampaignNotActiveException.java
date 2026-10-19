package io.github.eytoptas.teamchest.application.campaign;

import io.github.eytoptas.teamchest.application.TeamChestException;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;

public class CampaignNotActiveException extends TeamChestException {

    private final long campaignId;
    private final CampaignPhase phase;

    public CampaignNotActiveException(long campaignId, CampaignPhase phase) {
        super("Campaign " + campaignId + " is " + phase + " and does not allow this operation");
        this.campaignId = campaignId;
        this.phase = phase;
    }

    public long getCampaignId() {
        return campaignId;
    }

    public CampaignPhase getPhase() {
        return phase;
    }
}
