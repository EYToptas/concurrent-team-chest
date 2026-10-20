package io.github.eytoptas.teamchest.application.join;

import io.github.eytoptas.teamchest.application.Commands;
import io.github.eytoptas.teamchest.application.cache.TeamStateChanged;
import io.github.eytoptas.teamchest.application.campaign.CampaignCatalog;
import io.github.eytoptas.teamchest.application.transaction.RetryingTransactionRunner;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.campaign.CampaignPhase;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamAssignmentCursor;
import io.github.eytoptas.teamchest.persistence.team.MemberTeam;
import io.github.eytoptas.teamchest.persistence.team.TeamAssignmentCursorRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamMemberRepository;
import io.github.eytoptas.teamchest.persistence.team.TeamRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class JoinService implements DisposableBean {

    private final CampaignCatalog campaignCatalog;
    private final TeamAssignmentCursorRepository cursors;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final RetryingTransactionRunner transactions;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final JoinLockTimers lockTimers;
    private final JoinBatcher batcher;

    public JoinService(CampaignCatalog campaignCatalog, TeamAssignmentCursorRepository cursors, TeamRepository teams,
                       TeamMemberRepository members, RetryingTransactionRunner transactions,
                       ApplicationEventPublisher eventPublisher, Clock clock, JoinProperties settings,
                       MeterRegistry meters) {
        this.campaignCatalog = campaignCatalog;
        this.cursors = cursors;
        this.teams = teams;
        this.members = members;
        this.transactions = transactions;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.lockTimers = new JoinLockTimers(meters);
        this.batcher = settings.batching() ? new JoinBatcher(this::joinAll, settings, meters) : null;
    }

    public JoinResult join(String userId) {
        Commands.requireUserId(userId);
        Instant now = clock.instant();
        Campaign campaign = campaignCatalog.requireCurrentCampaign(CampaignPhase::allowsJoin, now);

        Optional<JoinResult> existing = existingMembership(campaign, userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        JoinBatcher.Request request = new JoinBatcher.Request(userId, now);
        if (batcher == null) {
            return joinAlone(campaign, request);
        }
        return switch (batcher.submit(campaign, request)) {
            case JoinBatcher.Joined joined -> joined.result();
            case JoinBatcher.RunAlone runAlone -> existingMembership(campaign, userId)
                    .orElseGet(() -> joinAlone(campaign, request));
            case JoinBatcher.Failed failed -> throw failed.exception();
        };
    }

    @Override
    public void destroy() {
        if (batcher != null) {
            batcher.close();
        }
    }

    int queuedJoins() {
        return batcher == null ? 0 : batcher.waiting();
    }

    private JoinResult joinAlone(Campaign campaign, JoinBatcher.Request request) {
        try {
            return transactions.execute(() -> joinUnderCursorLock(campaign, List.of(request)))
                    .get(request.userId());
        } catch (DuplicateMembershipException e) {
            return existingMembership(campaign, request.userId()).orElseThrow(() -> new IllegalStateException(
                    "Membership conflict for user " + request.userId() + " but no committed membership", e));
        }
    }

    private Map<String, JoinResult> joinAll(Campaign campaign, List<JoinBatcher.Request> requests) {
        return transactions.execute(() -> joinUnderCursorLock(campaign, requests));
    }

    private Map<String, JoinResult> joinUnderCursorLock(Campaign campaign, List<JoinBatcher.Request> requests) {
        TeamAssignmentCursor cursor = lockTimers.timeLock(() -> cursors.findByCampaignIdForUpdate(campaign.getId())
                .orElseThrow(() -> new IllegalStateException("Campaign " + campaign.getId() + " has no assignment cursor")));

        Map<String, Long> committed = members.findTeamsByCampaignIdAndUserIds(campaign.getId(),
                        requests.stream().map(JoinBatcher.Request::userId).toList())
                .stream().collect(Collectors.toMap(MemberTeam::userId, MemberTeam::teamId));

        Map<String, JoinResult> results = new LinkedHashMap<>();
        Map<Long, Team> changedTeams = new LinkedHashMap<>();
        Team team = null;
        for (JoinBatcher.Request request : requests) {
            Long existingTeamId = committed.get(request.userId());
            if (existingTeamId != null) {
                results.put(request.userId(), new JoinResult(campaign.getId(), existingTeamId, false));
                continue;
            }
            if (team == null || team.isFull()) {
                team = fillingTeam(cursor, campaign, request.requestedAt());
            }
            try {
                members.save(team.addMember(request.userId(), request.requestedAt()));
            } catch (DataIntegrityViolationException e) {
                throw new DuplicateMembershipException(e);
            }
            cursor.afterMemberJoined(team);
            changedTeams.put(team.getId(), team);
            results.put(request.userId(), new JoinResult(campaign.getId(), team.getId(), true));
        }

        for (Team changed : changedTeams.values()) {
            eventPublisher.publishEvent(new TeamStateChanged(campaign.getId(), changed.getId(), changed.getStateSeq()));
        }
        return results;
    }

    private Team fillingTeam(TeamAssignmentCursor cursor, Campaign campaign, Instant now) {
        return cursor.fillingTeamId()
                .map(teamId -> teams.findByIdForUpdate(teamId)
                        .orElseThrow(() -> new IllegalStateException("Filling team " + teamId + " does not exist")))
                .orElseGet(() -> openNewTeam(cursor, campaign, now));
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
