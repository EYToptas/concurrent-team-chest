package io.github.eytoptas.teamchest.application.claim;

import io.github.eytoptas.teamchest.application.cache.TeamStateChanged;
import io.github.eytoptas.teamchest.application.campaign.CampaignCatalog;
import io.github.eytoptas.teamchest.application.idempotency.IdempotentExecutor;
import io.github.eytoptas.teamchest.application.idempotency.OutcomeCodec;
import io.github.eytoptas.teamchest.application.transaction.UnexpectedConcurrentUpdateException;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import io.github.eytoptas.teamchest.domain.team.ClaimOutcome;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamMember;
import io.github.eytoptas.teamchest.persistence.team.TeamMemberRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ClaimService {

    private final IdempotentExecutor idempotentExecutor;
    private final CampaignCatalog campaignCatalog;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final OutcomeCodec<ClaimResult> codec;

    public ClaimService(IdempotentExecutor idempotentExecutor, CampaignCatalog campaignCatalog, TeamRepository teams,
                        TeamMemberRepository members, ApplicationEventPublisher eventPublisher, Clock clock,
                        JsonMapper jsonMapper) {
        this.idempotentExecutor = idempotentExecutor;
        this.campaignCatalog = campaignCatalog;
        this.teams = teams;
        this.members = members;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.codec = new OutcomeCodec<>(jsonMapper, Map.of(
                "SUCCESS", ClaimResult.Claimed.class,
                "CHEST_NOT_READY", ClaimResult.ChestNotReady.class,
                "ALREADY_CLAIMED", ClaimResult.AlreadyClaimed.class,
                "NOT_A_MEMBER", ClaimResult.NotAMember.class));
    }

    public ClaimResult claim(ClaimCommand command) {
        Instant now = clock.instant();
        return idempotentExecutor.execute(command.idempotentRequest(), codec,
                () -> campaignCatalog.requireCampaign(command.campaignId(), CampaignPhase::allowsClaim, now),
                campaign -> claimReward(campaign, command, now));
    }

    private ClaimResult claimReward(Campaign campaign, ClaimCommand command, Instant now) {
        Optional<Team> locked = teams.findTeamOfMemberForUpdate(campaign.getId(), command.userId());
        if (locked.isEmpty()) {
            return new ClaimResult.NotAMember(campaign.getId());
        }
        Team team = locked.get();
        if (!team.isCompleted()) {
            return new ClaimResult.ChestNotReady(campaign.getId(), team.getId(), team.getTotalPoints(), team.getTargetPoints());
        }
        TeamMember member = members.findByCampaignIdAndUserId(campaign.getId(), command.userId()).orElseThrow();

        return switch (team.claimReward(member, now)) {
            case ClaimOutcome.Claimed claimed -> {
                if (members.markClaimedIfUnclaimed(member.getId(), claimed.claimedAt()) != 1) {
                    throw new UnexpectedConcurrentUpdateException(
                            "Member " + member.getId() + " was claimed outside the team lock");
                }
                eventPublisher.publishEvent(new TeamStateChanged(campaign.getId(), team.getId(), team.getStateSeq()));
                yield new ClaimResult.Claimed(campaign.getId(), team.getId(), campaign.getReward(),
                        claimed.claimedAt(), team.getStateSeq());
            }
            case ClaimOutcome.ChestNotReady notReady -> new ClaimResult.ChestNotReady(
                    campaign.getId(), team.getId(), notReady.teamPoints(), notReady.targetPoints());
            case ClaimOutcome.AlreadyClaimed alreadyClaimed -> new ClaimResult.AlreadyClaimed(
                    campaign.getId(), team.getId(), alreadyClaimed.claimedAt());
        };
    }
}
