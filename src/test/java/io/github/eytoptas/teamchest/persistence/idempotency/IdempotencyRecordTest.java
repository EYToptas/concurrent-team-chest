package io.github.eytoptas.teamchest.persistence.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdempotencyRecordTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void startsPendingAndCompletesOnce() {
        IdempotencyRecord record = start("key-1", HASH);

        assertThat(record.isCompleted()).isFalse();
        record.complete("SUCCESS", "{}");

        assertThat(record.isCompleted()).isTrue();
        assertThat(record.getResultCode()).isEqualTo("SUCCESS");
        assertThatThrownBy(() -> record.complete("SUCCESS", "{}")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recognisesTheSameRequestByItsHash() {
        IdempotencyRecord record = start("key-1", HASH);

        assertThat(record.isForRequest(HASH)).isTrue();
        assertThat(record.isForRequest("b".repeat(64))).isFalse();
    }

    @Test
    void rejectsInvalidKeysAndHashes() {
        assertThatThrownBy(() -> start(" ", HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> start("k".repeat(65), HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> start("key-1", "abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnIncompleteOutcome() {
        IdempotencyRecord record = start("key-1", HASH);

        assertThatThrownBy(() -> record.complete(" ", "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record.complete("SUCCESS", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(record.isCompleted()).isFalse();
    }

    private static IdempotencyRecord start(String key, String hash) {
        return IdempotencyRecord.start(1L, "alice", IdempotentOperation.PROGRESS, key, hash, NOW);
    }
}
