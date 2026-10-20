package io.github.eytoptas.teamchest.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("team-chest")
public record TeamChestProperties(
        @DefaultValue CampaignSettings campaign,
        @DefaultValue TransactionSettings transaction) {

    public record CampaignSettings(
            @DefaultValue("7d") Duration claimGracePeriod,
            @DefaultValue("1s") Duration openCampaignsRefresh) {

        public CampaignSettings {
            if (claimGracePeriod.isNegative()) {
                throw new IllegalArgumentException("team-chest.campaign.claim-grace-period must not be negative");
            }
            if (openCampaignsRefresh.isNegative()) {
                throw new IllegalArgumentException("team-chest.campaign.open-campaigns-refresh must not be negative");
            }
        }
    }

    public record TransactionSettings(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("5ms") Duration minBackoff,
            @DefaultValue("25ms") Duration maxBackoff) {

        public TransactionSettings {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("team-chest.transaction.max-attempts must be at least 1");
            }
            if (minBackoff.isNegative() || maxBackoff.compareTo(minBackoff) < 0) {
                throw new IllegalArgumentException("team-chest.transaction backoff must satisfy 0 <= min <= max");
            }
        }
    }
}
