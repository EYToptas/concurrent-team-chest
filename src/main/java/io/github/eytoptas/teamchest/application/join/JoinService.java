package io.github.eytoptas.teamchest.application.join;

import io.github.eytoptas.teamchest.application.Commands;
import io.github.eytoptas.teamchest.application.cache.TeamStateChanged;
import io.github.eytoptas.teamchest.application.campaign.CampaignCatalog;
import io.github.eytoptas.teamchest.application.transaction.RetryingTransactionRunner;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamAssignmentCursor;
import io.github.eytoptas.teamchest.persistence.team.TeamAssignmentCursorRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamMemberRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class JoinService {

    private final CampaignCatalog campaignCatalog;
    private final TeamAssignmentCursorRepository cursors;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final RetryingTransactionRunner transactions;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public JoinService(CampaignCatalog campaignCatalog, TeamAssignmentCursorRepository cursors, TeamRepository teams,
                       TeamMemberRepository members, RetryingTransactionRunner transactions,
                       ApplicationEventPublisher eventPublisher, Clock clock) {
        this.campaignCatalog = campaignCatalog;
        this.cursors = cursors;
        this.teams = teams;
        this.members = members;
        this.transactions = transactions;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public JoinResult join(String userId) {
        Commands.requireUserId(userId);
        Instant now = clock.instant();
        Campaign campaign = campaignCatalog.requireCurrentCampaign(CampaignPhase::allowsJoin, now);

        Optional<JoinResult> existing = existingMembership(campaign, userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return transactions.execute(() -> joinUnderCursorLock(campaign, userId, now));
        } catch (DuplicateMembershipException e) {
            return existingMembership(campaign, userId).orElseThrow(() -> new IllegalStateException(
                    "Membership conflict for user " + userId + " but no committed membership", e));
        }
    }

    private JoinResult joinUnderCursorLock(Campaign campaign, String userId, Instant now) {
        TeamAssignmentCursor cursor = cursors.findByCampaignIdForUpdate(campaign.getId())
                .orElseThrow(() -> new IllegalStateException("Campaign " + campaign.getId() + " has no assignment cursor"));

        Optional<JoinResult> existing = existingMembership(campaign, userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Team team = cursor.fillingTeamId()
                .map(teamId -> teams.findByIdForUpdate(teamId)
                        .orElseThrow(() -> new IllegalStateException("Filling team " + teamId + " does not exist")))
                .orElseGet(() -> openNewTeam(cursor, campaign, now));

        try {
            members.save(team.addMember(userId, now));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateMembershipException(e);
        }
        cursor.afterMemberJoined(team);

        eventPublisher.publishEvent(new TeamStateChanged(campaign.getId(), team.getId(), team.getStateSeq()));
        return new JoinResult(campaign.getId(), team.getId(), true);
    }

    private Team openNewTeam(TeamAssignmentCursor cursor, Campaign campaign, Instant now) {
        Team team = teams.save(Team.createFor(campaign, now));
        cursor.startFilling(team);
        return team;
    }

    private Optional<JoinResult> existingMembership(Campaign campaign, String userId) {
        return members.findTeamIdByCampaignIdAndUserId(campaign.getId(), userId)
                .map(teamId -> new JoinResult(campaign.getId(), teamId, false));
    }

    static final class DuplicateMembershipException extends RuntimeException {

        DuplicateMembershipException(Throwable cause) {
            super("User is already a member of this campaign", cause);
        }
    }
}
