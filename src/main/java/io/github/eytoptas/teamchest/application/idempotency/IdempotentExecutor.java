package io.github.eytoptas.teamchest.application.idempotency;

import io.github.eytoptas.teamchest.application.transaction.RetryingTransactionRunner;
import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotencyRecord;
import io.github.eytoptas.teamchest.persistence.idempotency.IdempotencyRecordRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class IdempotentExecutor {

    private final IdempotencyRecordRepository records;
    private final RetryingTransactionRunner transactions;
    private final Clock clock;

    public IdempotentExecutor(IdempotencyRecordRepository records, RetryingTransactionRunner transactions,
                              Clock clock) {
        this.records = records;
        this.transactions = transactions;
        this.clock = clock;
    }

    public <R> R execute(IdempotentRequest request, OutcomeCodec<R> codec,
                         Supplier<Campaign> preconditions, Function<Campaign, R> work) {
        Optional<R> replayed = replay(request, codec);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Campaign campaign = preconditions.get();
        try {
            return transactions.execute(() -> {
                IdempotencyRecord record = insertPending(request);
                R outcome = work.apply(campaign);
                OutcomeCodec.StoredOutcome stored = codec.encode(outcome);
                record.complete(stored.code(), stored.payload());
                return outcome;
            });
        } catch (DuplicateIdempotencyKeyException e) {
            return replay(request, codec).orElseThrow(() -> new IllegalStateException(
                    "Idempotency key conflict but no committed record for key " + request.idempotencyKey(), e));
        }
    }

    private <R> Optional<R> replay(IdempotentRequest request, OutcomeCodec<R> codec) {
        return records.findByUserIdAndOperationAndIdempotencyKey(
                        request.userId(), request.operation(), request.idempotencyKey())
                .map(record -> {
                    if (!record.isForRequest(request.requestHash())) {
                        throw new IdempotencyKeyReuseException(request.idempotencyKey());
                    }
                    if (!record.isCompleted()) {
                        throw new IllegalStateException("Committed idempotency record " + record.getId() + " has no outcome");
                    }
                    return codec.decode(record.getResultCode(), record.getResultPayload());
                });
    }

    private IdempotencyRecord insertPending(IdempotentRequest request) {
        try {
            return records.saveAndFlush(IdempotencyRecord.start(request.campaignId(), request.userId(),
                    request.operation(), request.idempotencyKey(), request.requestHash(), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateIdempotencyKeyException(e);
        }
    }

    static final class DuplicateIdempotencyKeyException extends RuntimeException {

        DuplicateIdempotencyKeyException(Throwable cause) {
            super("Another request with the same idempotency key committed first", cause);
        }
    }
}
