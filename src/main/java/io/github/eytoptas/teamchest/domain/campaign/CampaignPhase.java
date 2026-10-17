package io.github.eytoptas.teamchest.domain.campaign;

public enum CampaignPhase {

    NOT_STARTED(false, false, false),
    ACTIVE(true, true, true),
    CLAIM_ONLY(false, false, true),
    CLOSED(false, false, false);

    private final boolean joinAllowed;
    private final boolean progressAllowed;
    private final boolean claimAllowed;

    CampaignPhase(boolean joinAllowed, boolean progressAllowed, boolean claimAllowed) {
        this.joinAllowed = joinAllowed;
        this.progressAllowed = progressAllowed;
        this.claimAllowed = claimAllowed;
    }

    public boolean allowsJoin() {
        return joinAllowed;
    }

    public boolean allowsProgress() {
        return progressAllowed;
    }

    public boolean allowsClaim() {
        return claimAllowed;
    }
}
