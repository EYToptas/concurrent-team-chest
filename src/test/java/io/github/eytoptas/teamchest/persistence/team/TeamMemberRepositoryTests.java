package io.github.eytoptas.teamchest.persistence.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.domain.team.ClaimOutcome;
import io.github.eytoptas.teamchest.domain.team.Team;
import io.github.eytoptas.teamchest.domain.team.TeamMember;
import io.github.eytoptas.teamchest.persistence.PersistenceIntegrationTest;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

class TeamMemberRepositoryTests extends PersistenceIntegrationTest {

    private static final Instant CLAIM_TIME = NOW.plusSeconds(60);
    private static final String CLAIM_UPDATE = "%UPDATE team_member SET claimed_at%";

    @Test
    void membershipIsIdentifiedByCampaignAndUser() {
        Campaign campaign = createCampaign();
        Campaign otherCampaign = createCampaign();
        Team team = createTeam(campaign);
        addMember(team, "alice");

        assertThat(members.findTeamIdByCampaignIdAndUserId(campaign.getId(), "alice")).contains(team.getId());
        assertThat(members.findTeamIdByCampaignIdAndUserId(otherCampaign.getId(), "alice")).isEmpty();
        assertThat(members.findTeamIdByCampaignIdAndUserId(campaign.getId(), "Alice")).isEmpty();
        assertThat(members.findByCampaignIdAndUserId(campaign.getId(), "alice"))
                .hasValueSatisfying(member -> assertThat(member.getTeamId()).isEqualTo(team.getId()));
    }

    @Test
    void secondMembershipInTheSameCampaignIsRejectedByTheDatabase() {
        Campaign campaign = createCampaign();
        Team team = createTeam(campaign);
        Team otherTeam = createTeam(campaign);
        addMember(team, "alice");

        assertThatThrownBy(() -> addMember(otherTeam, "alice"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(teams.findById(otherTeam.getId()).orElseThrow().getMemberCount()).isZero();
    }

    @Test
    void sameUserCanJoinDifferentCampaigns() {
        Team first = createTeam(createCampaign());
        Team second = createTeam(createCampaign());

        addMember(first, "alice");
        addMember(second, "alice");

        assertThat(members.findTeamIdByCampaignIdAndUserId(second.getCampaignId(), "alice")).contains(second.getId());
    }

    @Test
    void conditionalClaimAgreesWithTheManagedEntityInTheSameTransaction() {
        Campaign campaign = createCampaign();
        Team team = completedTeamWith(campaign, "alice");
        long seqBefore = teams.findById(team.getId()).orElseThrow().getStateSeq();

        runInTransaction(() -> {
            Team locked = teams.findByIdForUpdate(team.getId()).orElseThrow();
            TeamMember alice = members.findByCampaignIdAndUserId(campaign.getId(), "alice").orElseThrow();

            assertThat(locked.claimReward(alice, CLAIM_TIME)).isEqualTo(new ClaimOutcome.Claimed(CLAIM_TIME));
            assertThat(members.markClaimedIfUnclaimed(alice.getId(), CLAIM_TIME)).isEqualTo(1);
            assertThat(alice.getClaimedAt()).isEqualTo(CLAIM_TIME);
        });

        assertThat(storedClaimedAt(campaign, "alice")).isEqualTo("2026-10-18 12:01:00.123456");
        assertThat(teams.findById(team.getId()).orElseThrow().getStateSeq()).isEqualTo(seqBefore + 1);
        Integer secondAttempt = inTransaction(() -> members.markClaimedIfUnclaimed(memberId(campaign, "alice"), CLAIM_TIME));
        assertThat(secondAttempt).isZero();
    }

    @Test
    void dirtyCheckingNeverWritesClaimedAt() {
        Campaign campaign = createCampaign();
        Team team = completedTeamWith(campaign, "alice");

        runInTransaction(() -> {
            Team locked = teams.findByIdForUpdate(team.getId()).orElseThrow();
            TeamMember alice = members.findByCampaignIdAndUserId(campaign.getId(), "alice").orElseThrow();
            locked.claimReward(alice, CLAIM_TIME);
        });

        assertThat(storedClaimedAt(campaign, "alice")).isNull();
    }

    @Test
    void concurrentConditionalClaimsSucceedExactlyOnce() throws Exception {
        Campaign campaign = createCampaign();
        completedTeamWith(campaign, "alice");
        Long memberId = memberId(campaign, "alice");
        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<Integer> first = inTransactionAsync(() -> {
            int updated = members.markClaimedIfUnclaimed(memberId, CLAIM_TIME);
            firstUpdated.countDown();
            await(releaseFirst);
            return updated;
        });
        await(firstUpdated);
        Future<Integer> second = inTransactionAsync(() -> members.markClaimedIfUnclaimed(memberId, CLAIM_TIME.plusSeconds(1)));

        awaitBlockedStatements(CLAIM_UPDATE, 1);
        releaseFirst.countDown();

        assertThat(result(first)).isEqualTo(1);
        assertThat(result(second)).isZero();
        assertThat(storedClaimedAt(campaign, "alice")).isEqualTo("2026-10-18 12:01:00.123456");
    }

    @Test
    void conditionalClaimOutsideATransactionIsRejected() {
        Campaign campaign = createCampaign();
        completedTeamWith(campaign, "alice");
        Long memberId = memberId(campaign, "alice");

        assertThatThrownBy(() -> members.markClaimedIfUnclaimed(memberId, CLAIM_TIME))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Team completedTeamWith(Campaign campaign, String userId) {
        Team team = createTeam(campaign);
        addMember(team, userId);
        applyPoints(team, userId, 300);
        return team;
    }

    private Long memberId(Campaign campaign, String userId) {
        return members.findByCampaignIdAndUserId(campaign.getId(), userId).orElseThrow().getId();
    }

    private String storedClaimedAt(Campaign campaign, String userId) {
        return jdbc.queryForObject("""
                SELECT DATE_FORMAT(claimed_at, '%Y-%m-%d %H:%i:%s.%f') FROM team_member
                WHERE campaign_id = ? AND user_id = ?
                """, String.class, campaign.getId(), userId);
    }
}
