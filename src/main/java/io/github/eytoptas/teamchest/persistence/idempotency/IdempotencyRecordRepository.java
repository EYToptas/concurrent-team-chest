package io.github.eytoptas.teamchest.persistence.idempotency;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface IdempotencyRecordRepository extends Repository<IdempotencyRecord, Long> {

    @Transactional(propagation = Propagation.MANDATORY)
    IdempotencyRecord saveAndFlush(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByUserIdAndOperationAndIdempotencyKey(
            String userId, IdempotentOperation operation, String idempotencyKey);

    @Modifying
    @Query(value = "DELETE FROM idempotency_record WHERE campaign_id = :campaignId LIMIT :batchSize", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    int deleteBatchByCampaignId(@Param("campaignId") Long campaignId, @Param("batchSize") int batchSize);
}
