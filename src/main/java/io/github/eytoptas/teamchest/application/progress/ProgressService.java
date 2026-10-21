package io.github.eytoptas.teamchest.application.progress;

import io.github.eytoptas.teamchest.application.cache.TeamStateChanged;
import io.github.eytoptas.teamchest.application.campaign.CampaignCatalog;
import io.github.eytoptas.teamchest.application.idempotency.IdempotentExecutor;
import io.github.eytoptas.teamchest.application.idempotency.OutcomeCodec;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import io.github.eytoptas.teamchest.domain.team.ProgressOutcome;
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
public class ProgressService {

    private final IdempotentExecutor idempotentExecutor;
    private final CampaignCatalog campaignCatalog;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final OutcomeCodec<ProgressResult> codec;

    public ProgressService(IdempotentExecutor idempotentExecutor, CampaignCatalog campaignCatalog, TeamRepository teams,
                           TeamMemberRepository members, ApplicationEventPublisher eventPublisher, Clock clock,
                           JsonMapper jsonMapper) {
        this.idempotentExecutor = idempotentExecutor;
        this.campaignCatalog = campaignCatalog;
        this.teams = teams;
        this.members = members;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.codec = new OutcomeCodec<>(jsonMapper, Map.of(
                "SUCCESS", ProgressResult.Applied.class,
                "TEAM_COMPLETED", ProgressResult.TeamAlreadyCompleted.class,
                "NOT_A_MEMBER", ProgressResult.NotAMember.class));
    }

    public ProgressResult progress(ProgressCommand command) {
        Instant now = clock.instant();
        return idempotentExecutor.execute(command.idempotentRequest(), codec,
                () -> campaignCatalog.requireCampaign(command.campaignId(), CampaignPhase::allowsProgress, now),
                campaign -> applyPoints(campaign, command, now));
    }

    private ProgressResult applyPoints(Campaign campaign, ProgressCommand command, Instant now) {
        Optional<Team> locked = teams.findTeamOfMemberForUpdate(campaign.getId(), command.userId());
        if (locked.isEmpty()) {
            return new ProgressResult.NotAMember(campaign.getId());
        }
        Team team = locked.get();
        if (team.isCompleted()) {
            return new ProgressResult.TeamAlreadyCompleted(campaign.getId(), team.getId(), campaign.getTargetPoints());
        }
        TeamMember member = members.findByCampaignIdAndUserId(campaign.getId(), command.userId()).orElseThrow();

        return switch (team.applyPoints(member, command.points(), now)) {
            case ProgressOutcome.Applied applied -> {
                eventPublisher.publishEvent(new TeamStateChanged(campaign.getId(), team.getId(), team.getStateSeq()));
                yield new ProgressResult.Applied(campaign.getId(), team.getId(), applied.requestedPoints(),
                        applied.appliedPoints(), applied.memberPoints(), applied.teamPoints(), applied.targetPoints(),
                        applied.teamCompleted(), team.getStateSeq());
            }
            case ProgressOutcome.TeamAlreadyCompleted completed ->
                    new ProgressResult.TeamAlreadyCompleted(campaign.getId(), team.getId(), completed.targetPoints());
        };
    }
}
