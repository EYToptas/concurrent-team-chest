package io.github.eytoptas.teamchest.application.campaign;

import io.github.eytoptas.teamchest.application.TeamChestException;

public class NoActiveCampaignException extends TeamChestException {

    public NoActiveCampaignException() {
        super("No campaign is currently open for this operation");
    }
}
