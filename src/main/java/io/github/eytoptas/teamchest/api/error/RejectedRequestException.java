package io.github.eytoptas.teamchest.api.error;

import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import java.util.LinkedHashMap;
import java.util.Map;

public class RejectedRequestException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    private RejectedRequestException(ErrorCode code, Map<String, Object> details) {
        super(code.name());
        this.code = code;
        this.details = details;
    }

    public static RejectedRequestException teamCompleted(ProgressResult.TeamAlreadyCompleted rejection) {
        return new RejectedRequestException(ErrorCode.TEAM_COMPLETED,
                details("campaignId", rejection.campaignId(), "teamId", rejection.teamId(),
                        "targetPoints", rejection.targetPoints()));
    }

    public static RejectedRequestException chestNotReady(ClaimResult.ChestNotReady rejection) {
        return new RejectedRequestException(ErrorCode.CHEST_NOT_READY,
                details("campaignId", rejection.campaignId(), "teamId", rejection.teamId(),
                        "teamPoints", rejection.teamPoints(), "targetPoints", rejection.targetPoints()));
    }

    public static RejectedRequestException alreadyClaimed(ClaimResult.AlreadyClaimed rejection) {
        return new RejectedRequestException(ErrorCode.ALREADY_CLAIMED,
                details("campaignId", rejection.campaignId(), "teamId", rejection.teamId(),
                        "claimedAt", rejection.claimedAt()));
    }

    public static RejectedRequestException notAMember(long campaignId) {
        return new RejectedRequestException(ErrorCode.NOT_A_MEMBER,
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
