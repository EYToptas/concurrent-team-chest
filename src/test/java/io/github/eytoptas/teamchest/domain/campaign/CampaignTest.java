package io.github.eytoptas.teamchest.domain.campaign;

import static io.github.eytoptas.teamchest.domain.DomainFixtures.CAMPAIGN_END;
import static io.github.eytoptas.teamchest.domain.DomainFixtures.CAMPAIGN_START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CampaignTest {

    private static final Duration GRACE = Duration.ofDays(7);
    private static final String REWARD = "{\"coins\": 500}";

    private final Campaign campaign = new Campaign("team-chest", 30, 300, REWARD, CAMPAIGN_START, CAMPAIGN_END);

    @Test
    void isNotStartedBeforeItsStart() {
        assertThat(campaign.phaseAt(CAMPAIGN_START.minusNanos(1), GRACE)).isEqualTo(CampaignPhase.NOT_STARTED);
    }

    @Test
    void isActiveFromStartInclusiveUntilEndExclusive() {
        assertThat(campaign.phaseAt(CAMPAIGN_START, GRACE)).isEqualTo(CampaignPhase.ACTIVE);
        assertThat(campaign.phaseAt(CAMPAIGN_END.minusNanos(1), GRACE)).isEqualTo(CampaignPhase.ACTIVE);
    }

    @Test
    void isClaimOnlyFromEndUntilGracePeriodExpires() {
        assertThat(campaign.phaseAt(CAMPAIGN_END, GRACE)).isEqualTo(CampaignPhase.CLAIM_ONLY);
        assertThat(campaign.phaseAt(CAMPAIGN_END.plus(GRACE).minusNanos(1), GRACE)).isEqualTo(CampaignPhase.CLAIM_ONLY);
    }

    @Test
    void isClosedOnceGracePeriodExpired() {
        assertThat(campaign.phaseAt(CAMPAIGN_END.plus(GRACE), GRACE)).isEqualTo(CampaignPhase.CLOSED);
        assertThat(campaign.claimWindowEnd(GRACE)).isEqualTo(CAMPAIGN_END.plus(GRACE));
    }

    @Test
    void zeroGracePeriodClosesTheCampaignAtItsEnd() {
        assertThat(campaign.phaseAt(CAMPAIGN_END, Duration.ZERO)).isEqualTo(CampaignPhase.CLOSED);
    }

    @Test
    void rejectsNegativeGracePeriod() {
        assertThatThrownBy(() -> campaign.phaseAt(CAMPAIGN_START, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void phasesAllowOnlyTheirOperations() {
        assertThat(CampaignPhase.ACTIVE.allowsJoin()).isTrue();
        assertThat(CampaignPhase.ACTIVE.allowsProgress()).isTrue();
        assertThat(CampaignPhase.ACTIVE.allowsClaim()).isTrue();

        assertThat(CampaignPhase.CLAIM_ONLY.allowsJoin()).isFalse();
        assertThat(CampaignPhase.CLAIM_ONLY.allowsProgress()).isFalse();
        assertThat(CampaignPhase.CLAIM_ONLY.allowsClaim()).isTrue();

        for (CampaignPhase phase : new CampaignPhase[] {CampaignPhase.NOT_STARTED, CampaignPhase.CLOSED}) {
            assertThat(phase.allowsJoin()).isFalse();
            assertThat(phase.allowsProgress()).isFalse();
            assertThat(phase.allowsClaim()).isFalse();
        }
    }

    @Test
    void onlyActiveAndClaimOnlyPhasesAreOpen() {
        assertThat(CampaignPhase.ACTIVE.isOpen()).isTrue();
        assertThat(CampaignPhase.CLAIM_ONLY.isOpen()).isTrue();
        assertThat(CampaignPhase.NOT_STARTED.isOpen()).isFalse();
        assertThat(CampaignPhase.CLOSED.isOpen()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Short.MAX_VALUE + 1})
    void rejectsInvalidTeamCapacity(int capacity) {
        assertThatThrownBy(() -> new Campaign("e", capacity, 300, REWARD, CAMPAIGN_START, CAMPAIGN_END))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveTargetPoints(int targetPoints) {
        assertThatThrownBy(() -> new Campaign("e", 30, targetPoints, REWARD, CAMPAIGN_START, CAMPAIGN_END))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWindowThatDoesNotEndAfterItStarts() {
        assertThatThrownBy(() -> new Campaign("e", 30, 300, REWARD, CAMPAIGN_START, CAMPAIGN_START))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Campaign("e", 30, 300, REWARD, CAMPAIGN_END, CAMPAIGN_START))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankCode() {
        assertThatThrownBy(() -> new Campaign(" ", 30, 300, REWARD, CAMPAIGN_START, CAMPAIGN_END))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsItsConfiguration() {
        Instant start = CAMPAIGN_START;

        assertThat(campaign.getCode()).isEqualTo("team-chest");
        assertThat(campaign.getTeamCapacity()).isEqualTo((short) 30);
        assertThat(campaign.getTargetPoints()).isEqualTo(300);
        assertThat(campaign.getReward()).isEqualTo(REWARD);
        assertThat(campaign.getStartsAt()).isEqualTo(start);
        assertThat(campaign.getEndsAt()).isEqualTo(CAMPAIGN_END);
    }
}
