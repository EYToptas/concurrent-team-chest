package io.github.eytoptas.teamchest.api.error;

import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import java.util.LinkedHashMap;
import java.util.Map;

public class RejectedRequestException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    private RejectedRequestException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public static RejectedRequestException teamCompleted(ProgressResult.TeamAlreadyCompleted rejection) {
        return new RejectedRequestException(ErrorCode.TEAM_COMPLETED, "The team has already reached its target",
                details("campaignId", rejection.campaignId(), "teamId", rejection.teamId(),
                        "targetPoints", rejection.targetPoints()));
    }

    public static RejectedRequestException chestNotReady(ClaimResult.ChestNotReady rejection) {
        return new RejectedRequestException(ErrorCode.CHEST_NOT_READY, "The team has not reached its target yet",
                details("campaignId", rejection.campaignId(), "teamId", rejection.teamId(),
                        "teamPoints", rejection.teamPoints(), "targetPoints", rejection.targetPoints()));
    }

    public static RejectedRequestException alreadyClaimed(ClaimResult.AlreadyClaimed rejection) {
        return new RejectedRequestException(ErrorCode.ALREADY_CLAIMED, "The chest reward was already claimed",
                details("campaignId", rejection.campaignId(), "teamId", rejection.teamId(),
                        "claimedAt", rejection.claimedAt()));
    }

    public static RejectedRequestException notAMember(long campaignId) {
        return new RejectedRequestException(ErrorCode.NOT_A_MEMBER, "The user has not joined this campaign",
                details("campaignId", campaignId));
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    private static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            details.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return details;
    }
}
