package io.github.eytoptas.teamchest.persistence.team;

import io.github.eytoptas.teamchest.domain.team.TeamAssignmentCursor;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface TeamAssignmentCursorRepository extends Repository<TeamAssignmentCursor, Long> {

    TeamAssignmentCursor save(TeamAssignmentCursor cursor);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TeamAssignmentCursor c where c.campaignId = :campaignId")
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<TeamAssignmentCursor> findByCampaignIdForUpdate(@Param("campaignId") Long campaignId);
}
