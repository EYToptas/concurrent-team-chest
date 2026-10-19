package io.github.eytoptas.teamchest.api;

import io.github.eytoptas.teamchest.api.error.RejectedRequestException;
import io.github.eytoptas.teamchest.api.ratelimit.RateLimited;
import io.github.eytoptas.teamchest.api.request.ClaimRequest;
import io.github.eytoptas.teamchest.api.request.ProgressRequest;
import io.github.eytoptas.teamchest.api.response.ClaimResponse;
import io.github.eytoptas.teamchest.api.response.JoinResponse;
import io.github.eytoptas.teamchest.api.response.ProgressResponse;
import io.github.eytoptas.teamchest.api.response.TeamOverviewResponse;
import io.github.eytoptas.teamchest.application.claim.ClaimCommand;
import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import io.github.eytoptas.teamchest.application.claim.ClaimService;
import io.github.eytoptas.teamchest.application.join.JoinService;
import io.github.eytoptas.teamchest.application.progress.ProgressCommand;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import io.github.eytoptas.teamchest.application.progress.ProgressService;
import io.github.eytoptas.teamchest.application.query.TeamQueryService;
import io.github.eytoptas.teamchest.application.ratelimit.RateLimitedOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TeamChestController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._:-]{1,64}";
    private static final String IDEMPOTENCY_KEY_MESSAGE = "must be 1-64 characters of A-Z a-z 0-9 . _ : -";

    private final JoinService joinService;
    private final ProgressService progressService;
    private final ClaimService claimService;
    private final TeamQueryService teamQueryService;

    public TeamChestController(JoinService joinService, ProgressService progressService, ClaimService claimService,
                               TeamQueryService teamQueryService) {
        this.joinService = joinService;
        this.progressService = progressService;
        this.claimService = claimService;
        this.teamQueryService = teamQueryService;
    }

    @PostMapping("/join")
    @RateLimited(RateLimitedOperation.JOIN)
    public JoinResponse join(@CurrentUserId String userId) {
        return JoinResponse.from(joinService.join(userId));
    }

    @PostMapping(path = "/progress", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitedOperation.PROGRESS)
    public ProgressResponse progress(
            @CurrentUserId String userId,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE)
            String idempotencyKey,
            @Valid @RequestBody ProgressRequest request) {
        ProgressResult result = progressService.progress(
                new ProgressCommand(userId, request.campaignId(), request.points(), idempotencyKey));
        return switch (result) {
            case ProgressResult.Applied applied -> ProgressResponse.from(applied);
            case ProgressResult.TeamAlreadyCompleted rejected -> throw RejectedRequestException.teamCompleted(rejected);
            case ProgressResult.NotAMember rejected -> throw RejectedRequestException.notAMember(rejected.campaignId());
        };
    }

    @PostMapping(path = "/claim", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitedOperation.CLAIM)
    public ClaimResponse claim(
            @CurrentUserId String userId,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE)
            String idempotencyKey,
            @Valid @RequestBody ClaimRequest request) {
        ClaimResult result = claimService.claim(new ClaimCommand(userId, request.campaignId(), idempotencyKey));
        return switch (result) {
            case ClaimResult.Claimed claimed -> ClaimResponse.from(claimed);
            case ClaimResult.ChestNotReady rejected -> throw RejectedRequestException.chestNotReady(rejected);
            case ClaimResult.AlreadyClaimed rejected -> throw RejectedRequestException.alreadyClaimed(rejected);
            case ClaimResult.NotAMember rejected -> throw RejectedRequestException.notAMember(rejected.campaignId());
        };
    }

    @GetMapping("/get")
    @RateLimited(RateLimitedOperation.GET)
    public TeamOverviewResponse get(@CurrentUserId String userId) {
        return TeamOverviewResponse.from(teamQueryService.overviewFor(userId));
    }
}
