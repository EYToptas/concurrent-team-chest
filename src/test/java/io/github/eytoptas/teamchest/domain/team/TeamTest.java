package io.github.eytoptas.teamchest.domain.team;

import static io.github.eytoptas.teamchest.domain.DomainFixtures.T0;
import static io.github.eytoptas.teamchest.domain.DomainFixtures.persistedCampaign;
import static io.github.eytoptas.teamchest.domain.DomainFixtures.persistedTeam;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.domain.DomainFixtures;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TeamTest {

    private final Campaign campaign = persistedCampaign(1L);
    private Team team;

    @BeforeEach
    void createTeam() {
        team = persistedTeam(campaign, 10L);
    }

    @Nested
    class Creation {

        @Test
        void copiesTheCampaignLimitsAndStartsEmpty() {
            assertThat(team.getCampaignId()).isEqualTo(1L);
            assertThat(team.getCapacity()).isEqualTo((short) 30);
            assertThat(team.getTargetPoints()).isEqualTo(300);
            assertThat(team.getMemberCount()).isZero();
            assertThat(team.getTotalPoints()).isZero();
            assertThat(team.getStateSeq()).isZero();
            assertThat(team.isCompleted()).isFalse();
            assertThat(team.getCreatedAt()).isEqualTo(T0);
        }

        @Test
        void requiresAPersistedCampaign() {
            Campaign unsaved = new Campaign("unsaved", 30, 300, "{}", DomainFixtures.CAMPAIGN_START, DomainFixtures.CAMPAIGN_END);

            assertThatThrownBy(() -> Team.createFor(unsaved, T0)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class Membership {

        @Test
        void addingAMemberIncrementsCountAndSequence() {
            TeamMember alice = team.addMember("alice", T0);

            assertThat(team.getMemberCount()).isEqualTo((short) 1);
            assertThat(team.getStateSeq()).isEqualTo(1);
            assertThat(alice.getUserId()).isEqualTo("alice");
            assertThat(alice.getTeamId()).isEqualTo(10L);
            assertThat(alice.getCampaignId()).isEqualTo(1L);
            assertThat(alice.getPoints()).isZero();
            assertThat(alice.getPointsReachedSeq()).isEqualTo(1);
            assertThat(alice.getJoinedAt()).isEqualTo(T0);
            assertThat(alice.hasClaimed()).isFalse();
        }

        @Test
        void membersWithZeroPointsRankByJoinOrder() {
            TeamMember first = team.addMember("first", T0);
            TeamMember second = team.addMember("second", T0);

            assertThat(first.getPointsReachedSeq()).isLessThan(second.getPointsReachedSeq());
        }

        @Test
        void acceptsExactlyCapacityMembers() {
            for (int i = 1; i <= 30; i++) {
                assertThat(team.isFull()).isFalse();
                team.addMember("user-" + i, T0);
            }

            assertThat(team.isFull()).isTrue();
            assertThat(team.getMemberCount()).isEqualTo((short) 30);
            assertThatThrownBy(() -> team.addMember("user-31", T0)).isInstanceOf(IllegalStateException.class);
            assertThat(team.getMemberCount()).isEqualTo((short) 30);
            assertThat(team.getStateSeq()).isEqualTo(30);
        }

        @Test
        void respectsASmallerConfiguredCapacity() {
            Team pair = persistedTeam(persistedCampaign(2L, 2, 300), 20L);
            pair.addMember("a", T0);
            pair.addMember("b", T0);

            assertThat(pair.isFull()).isTrue();
            assertThatThrownBy(() -> pair.addMember("c", T0)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void requiresAPersistedTeam() {
            Team unsaved = Team.createFor(campaign, T0);

            assertThatThrownBy(() -> unsaved.addMember("alice", T0)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void rejectsBlankUserId() {
            assertThatThrownBy(() -> team.addMember(" ", T0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(team.getMemberCount()).isZero();
        }
    }

    @Nested
    class Progress {

        private TeamMember alice;
        private TeamMember bob;
        private TeamMember carol;

        @BeforeEach
        void joinSampleTeam() {
            alice = team.addMember("alice", T0);
            bob = team.addMember("bob", T0);
            carol = team.addMember("carol", T0);
        }

        @Test
        void appliesTheFullAmountWhileBelowTarget() {
            ProgressOutcome outcome = team.applyPoints(alice, 6, T0);

            assertThat(outcome).isEqualTo(new ProgressOutcome.Applied(6, 6, 6, 6, 300, false));
            assertThat(team.getTotalPoints()).isEqualTo(6);
            assertThat(alice.getPoints()).isEqualTo(6);
            assertThat(team.isCompleted()).isFalse();
        }

        @Test
        void clipsTheRequestThatCrossesTheTargetAndRejectsTheNextOne() {
            team.applyPoints(alice, 100, T0);
            team.applyPoints(bob, 100, T0);
            team.applyPoints(carol, 90, T0);
            assertThat(team.getTotalPoints()).isEqualTo(290);

            ProgressOutcome aliceOutcome = team.applyPoints(alice, 6, T0);
            ProgressOutcome bobOutcome = team.applyPoints(bob, 5, T0.plusSeconds(1));
            ProgressOutcome carolOutcome = team.applyPoints(carol, 3, T0.plusSeconds(2));

            assertThat(aliceOutcome).isEqualTo(new ProgressOutcome.Applied(6, 6, 106, 296, 300, false));
            assertThat(bobOutcome).isEqualTo(new ProgressOutcome.Applied(5, 4, 104, 300, 300, true));
            assertThat(carolOutcome).isEqualTo(new ProgressOutcome.TeamAlreadyCompleted(300, 300));

            assertThat(alice.getPoints()).isEqualTo(106);
            assertThat(bob.getPoints()).isEqualTo(104);
            assertThat(carol.getPoints()).isEqualTo(90);
            assertThat(team.getTotalPoints()).isEqualTo(300);
            assertThat(team.getCompletedAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        void requestReachingTheTargetExactlyCompletesTheTeam() {
            team.applyPoints(alice, 299, T0);

            ProgressOutcome outcome = team.applyPoints(bob, 1, T0);

            assertThat(outcome).isEqualTo(new ProgressOutcome.Applied(1, 1, 1, 300, 300, true));
            assertThat(team.isCompleted()).isTrue();
        }

        @Test
        void singleRequestAboveTheTargetIsClippedToTheTarget() {
            ProgressOutcome outcome = team.applyPoints(alice, 1_000, T0);

            assertThat(outcome).isEqualTo(new ProgressOutcome.Applied(1_000, 300, 300, 300, 300, true));
        }

        @Test
        void maximumIntRequestDoesNotOverflow() {
            team.applyPoints(alice, 150, T0);

            ProgressOutcome outcome = team.applyPoints(bob, Integer.MAX_VALUE, T0);

            assertThat(outcome).isEqualTo(new ProgressOutcome.Applied(Integer.MAX_VALUE, 150, 150, 300, 300, true));
        }

        @Test
        void rejectionAfterCompletionChangesNothing() {
            team.applyPoints(alice, 300, T0);
            long seqAtCompletion = team.getStateSeq();
            long bobSeq = bob.getPointsReachedSeq();

            ProgressOutcome outcome = team.applyPoints(bob, 5, T0.plusSeconds(10));

            assertThat(outcome).isInstanceOf(ProgressOutcome.TeamAlreadyCompleted.class);
            assertThat(team.getStateSeq()).isEqualTo(seqAtCompletion);
            assertThat(team.getCompletedAt()).isEqualTo(T0);
            assertThat(bob.getPoints()).isZero();
            assertThat(bob.getPointsReachedSeq()).isEqualTo(bobSeq);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        void rejectsNonPositivePointsWithoutChangingState(int points) {
            long seqBefore = team.getStateSeq();

            assertThatThrownBy(() -> team.applyPoints(alice, points, T0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(team.getTotalPoints()).isZero();
            assertThat(team.getStateSeq()).isEqualTo(seqBefore);
        }

        @Test
        void rejectsAMemberOfAnotherTeam() {
            Team otherTeam = persistedTeam(campaign, 11L);
            TeamMember stranger = otherTeam.addMember("stranger", T0);

            assertThatThrownBy(() -> team.applyPoints(stranger, 5, T0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(team.getTotalPoints()).isZero();
            assertThat(stranger.getPoints()).isZero();
        }

        @Test
        void everyAppliedRequestAdvancesTheSequenceAndStampsTheMember() {
            long seqBefore = team.getStateSeq();

            team.applyPoints(alice, 5, T0);

            assertThat(team.getStateSeq()).isEqualTo(seqBefore + 1);
            assertThat(alice.getPointsReachedSeq()).isEqualTo(team.getStateSeq());
        }

        @Test
        void memberWhoReachedEqualPointsFirstHasTheLowerSequence() {
            team.applyPoints(bob, 5, T0);
            team.applyPoints(alice, 5, T0);

            assertThat(bob.getPoints()).isEqualTo(alice.getPoints());
            assertThat(bob.getPointsReachedSeq()).isLessThan(alice.getPointsReachedSeq());
        }

        @Test
        void teamPointsAlwaysEqualTheSumOfMemberPoints() {
            int[] requests = {7, 13, 50, 99, 1, 64, 80, 3};
            TeamMember[] members = {alice, bob, carol};

            for (int i = 0; i < requests.length; i++) {
                team.applyPoints(members[i % members.length], requests[i], T0);
                int memberSum = alice.getPoints() + bob.getPoints() + carol.getPoints();
                assertThat(team.getTotalPoints()).isEqualTo(memberSum).isLessThanOrEqualTo(300);
            }
            assertThat(team.getTotalPoints()).isEqualTo(300);
        }
    }

    @Nested
    class Claim {

        private TeamMember alice;
        private TeamMember bob;
        private TeamMember carol;

        @BeforeEach
        void joinSampleTeam() {
            alice = team.addMember("alice", T0);
            bob = team.addMember("bob", T0);
            carol = team.addMember("carol", T0);
        }

        @Test
        void isRejectedBeforeTheTeamReachesItsTarget() {
            team.applyPoints(alice, 290, T0);
            long seqBefore = team.getStateSeq();

            ClaimOutcome outcome = team.claimReward(carol, T0);

            assertThat(outcome).isEqualTo(new ClaimOutcome.ChestNotReady(290, 300));
            assertThat(carol.hasClaimed()).isFalse();
            assertThat(team.getStateSeq()).isEqualTo(seqBefore);
        }

        @Test
        void everyMemberOfACompletedTeamCanClaimExactlyOnce() {
            team.applyPoints(alice, 290, T0);
            assertThat(team.claimReward(carol, T0)).isInstanceOf(ClaimOutcome.ChestNotReady.class);
            team.applyPoints(alice, 6, T0);
            team.applyPoints(bob, 5, T0);

            Instant claimTime = T0.plusSeconds(60);
            assertThat(team.claimReward(carol, claimTime)).isEqualTo(new ClaimOutcome.Claimed(claimTime));
            assertThat(team.claimReward(alice, claimTime)).isEqualTo(new ClaimOutcome.Claimed(claimTime));
            assertThat(team.claimReward(bob, claimTime)).isEqualTo(new ClaimOutcome.Claimed(claimTime));

            ClaimOutcome secondClaim = team.claimReward(alice, claimTime.plusSeconds(5));

            assertThat(secondClaim).isEqualTo(new ClaimOutcome.AlreadyClaimed(claimTime));
            assertThat(alice.getClaimedAt()).isEqualTo(claimTime);
        }

        @Test
        void successfulClaimAdvancesTheSequenceAndRejectionsDoNot() {
            team.applyPoints(alice, 300, T0);
            long seqBefore = team.getStateSeq();

            team.claimReward(bob, T0);
            assertThat(team.getStateSeq()).isEqualTo(seqBefore + 1);

            team.claimReward(bob, T0);
            assertThat(team.getStateSeq()).isEqualTo(seqBefore + 1);
        }

        @Test
        void claimingDoesNotChangePointsOrRanking() {
            team.applyPoints(alice, 300, T0);
            int points = alice.getPoints();
            long reachedSeq = alice.getPointsReachedSeq();

            team.claimReward(alice, T0);

            assertThat(alice.getPoints()).isEqualTo(points);
            assertThat(alice.getPointsReachedSeq()).isEqualTo(reachedSeq);
        }

        @Test
        void rejectsAMemberOfAnotherTeam() {
            team.applyPoints(alice, 300, T0);
            TeamMember stranger = persistedTeam(campaign, 11L).addMember("stranger", T0);

            assertThatThrownBy(() -> team.claimReward(stranger, T0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(stranger.hasClaimed()).isFalse();
        }
    }
}
