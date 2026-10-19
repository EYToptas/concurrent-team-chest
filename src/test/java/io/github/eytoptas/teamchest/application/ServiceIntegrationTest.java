package io.github.eytoptas.teamchest.application;

import io.github.eytoptas.teamchest.TestcontainersConfiguration;
import io.github.eytoptas.teamchest.application.claim.ClaimCommand;
import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import io.github.eytoptas.teamchest.application.claim.ClaimService;
import io.github.eytoptas.teamchest.application.join.JoinService;
import io.github.eytoptas.teamchest.application.progress.ProgressCommand;
import io.github.eytoptas.teamchest.application.progress.ProgressResult;
import io.github.eytoptas.teamchest.application.progress.ProgressService;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@Import({TestcontainersConfiguration.class, ServiceTestConfiguration.class})
public abstract class ServiceIntegrationTest extends PersistenceIntegrationTest {

    @Autowired
    protected JoinService joinService;
    @Autowired
    protected ProgressService progressService;
    @Autowired
    protected ClaimService claimService;
    @Autowired
    protected AfterCommitRecorder afterCommit;
    @Autowired
    protected JsonMapper jsonMapper;

    protected Campaign activeCampaign() {
        Instant now = Instant.now();
        return createCampaign(now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
    }

    protected Campaign endedCampaignInGracePeriod() {
        Instant now = Instant.now();
        return createCampaign(now.minus(Duration.ofDays(2)), now.minus(Duration.ofHours(1)));
    }

    protected Campaign closedCampaign() {
        Instant now = Instant.now();
        return createCampaign(now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(10)));
    }

    protected Team teamWith(Campaign campaign, String... userIds) {
        Team team = createTeam(campaign);
        for (String userId : userIds) {
            addMember(team, userId);
        }
        return team;
    }

    protected Team completedTeamWith(Campaign campaign, String... userIds) {
        Team team = teamWith(campaign, userIds);
        applyPoints(team, userIds[0], campaign.getTargetPoints());
        return team;
    }

    protected ProgressResult progress(Campaign campaign, String userId, int points) {
        return progressService.progress(new ProgressCommand(userId, campaign.getId(), points, newKey()));
    }

    protected ClaimResult claim(Campaign campaign, String userId) {
        return claimService.claim(new ClaimCommand(userId, campaign.getId(), newKey()));
    }

    protected int teamPoints(long teamId) {
        return jdbc.queryForObject("SELECT total_points FROM team WHERE id = ?", Integer.class, teamId);
    }

    protected int memberPointSum(long teamId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(points), 0) FROM team_member WHERE team_id = ?",
                Integer.class, teamId);
    }

    protected int idempotencyRecordCount(String userId, String key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, userId, key);
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }
}
