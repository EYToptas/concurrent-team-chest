package io.github.eytoptas.teamchest.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eytoptas.teamchest.application.claim.ClaimResult;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OutcomeCodecTest {

    private final OutcomeCodec<ClaimResult> codec = new OutcomeCodec<>(JsonMapper.builder().build(), Map.of(
            "SUCCESS", ClaimResult.Claimed.class,
            "CHEST_NOT_READY", ClaimResult.ChestNotReady.class,
            "ALREADY_CLAIMED", ClaimResult.AlreadyClaimed.class,
            "NOT_A_MEMBER", ClaimResult.NotAMember.class));

    @Test
    void everyOutcomeRoundTripsThroughItsCode() {
        Instant claimedAt = Instant.parse("2026-10-18T12:00:00.123456Z");
        ClaimResult[] outcomes = {
                new ClaimResult.Claimed(1, 10, "{\"coins\": 500}", claimedAt, 7),
                new ClaimResult.ChestNotReady(1, 10, 290, 300),
                new ClaimResult.AlreadyClaimed(1, 10, claimedAt),
                new ClaimResult.NotAMember(1)
        };

        for (ClaimResult outcome : outcomes) {
            OutcomeCodec.StoredOutcome stored = codec.encode(outcome);
            assertThat(codec.decode(stored.code(), stored.payload())).isEqualTo(outcome);
        }
        assertThat(codec.encode(outcomes[0]).code()).isEqualTo("SUCCESS");
        assertThat(codec.encode(outcomes[3]).code()).isEqualTo("NOT_A_MEMBER");
    }

    @Test
    void rejectsUnknownCodes() {
        assertThatThrownBy(() -> codec.decode("SOMETHING_ELSE", "{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnmappedTypes() {
        OutcomeCodec<ClaimResult> partial = new OutcomeCodec<>(JsonMapper.builder().build(),
                Map.of("NOT_A_MEMBER", ClaimResult.NotAMember.class));

        assertThatThrownBy(() -> partial.encode(new ClaimResult.ChestNotReady(1, 10, 0, 300)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsATypeMappedToTwoCodes() {
        assertThatThrownBy(() -> new OutcomeCodec<ClaimResult>(JsonMapper.builder().build(), Map.of(
                "A", ClaimResult.NotAMember.class,
                "B", ClaimResult.NotAMember.class))).isInstanceOf(IllegalArgumentException.class);
    }
}
