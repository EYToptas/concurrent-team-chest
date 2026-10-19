package io.github.eytoptas.teamchest.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eytoptas.teamchest.application.claim.ClaimCommand;
import io.github.eytoptas.teamchest.application.progress.ProgressCommand;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotentOperation;
import org.junit.jupiter.api.Test;

class IdempotentRequestTest {

    @Test
    void fingerprintIsAStableSha256Hex() {
        String fingerprint = IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, 1, 6);

        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, 1, 6)).isEqualTo(fingerprint);
    }

    @Test
    void fingerprintChangesWithOperationCampaignAndContent() {
        String base = IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, 1, 6);

        assertThat(IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, 1, 7)).isNotEqualTo(base);
        assertThat(IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, 2, 6)).isNotEqualTo(base);
        assertThat(IdempotentRequest.fingerprint(IdempotentOperation.CLAIM, 1, 6)).isNotEqualTo(base);
    }

    @Test
    void commandsBuildTheirRequestFromTheirContent() {
        ProgressCommand progress = new ProgressCommand("alice", 1, 6, "key-1");
        ClaimCommand claim = new ClaimCommand("alice", 1, "key-1");

        assertThat(progress.idempotentRequest().requestHash())
                .isEqualTo(IdempotentRequest.fingerprint(IdempotentOperation.PROGRESS, 1, 6));
        assertThat(claim.idempotentRequest().operation()).isEqualTo(IdempotentOperation.CLAIM);
        assertThat(claim.idempotentRequest().requestHash())
                .isEqualTo(IdempotentRequest.fingerprint(IdempotentOperation.CLAIM, 1));
    }
}
