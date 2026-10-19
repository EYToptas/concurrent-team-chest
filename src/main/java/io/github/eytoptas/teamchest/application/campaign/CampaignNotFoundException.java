package io.github.eytoptas.teamchest.application.campaign;

import io.github.eytoptas.teamchest.application.TeamChestException;

public class CampaignNotFoundException extends TeamChestException {

    private final long campaignId;

    public CampaignNotFoundException(long campaignId) {
        super("Campaign " + campaignId + " does not exist");
        this.campaignId = campaignId;
    }

    public long getCampaignId() {
        return campaignId;
    }
}
