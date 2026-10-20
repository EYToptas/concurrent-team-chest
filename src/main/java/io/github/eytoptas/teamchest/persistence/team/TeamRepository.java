package io.github.eytoptas.teamchest.persistence.team;

import io.github.eytoptas.teamchest.domain.team.Team;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface TeamRepository extends Repository<Team, Long> {

    Team save(Team team);

    Optional<Team> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.id = :id")
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Team> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select new io.github.eytoptas.teamchest.persistence.team.TeamRankingRow(
                t.id, t.campaignId, t.memberCount, t.totalPoints, t.targetPoints, t.completedAt, t.stateSeq,
                m.userId, m.points, m.pointsReachedSeq, m.claimedAt)
            from Team t
            join TeamMember m on m.teamId = t.id and m.campaignId = t.campaignId
            where t.id = :teamId and t.campaignId = :campaignId
              and m.teamId = :teamId and m.campaignId = :campaignId
            order by m.points desc, m.pointsReachedSeq asc
            """)
    List<TeamRankingRow> findRanking(@Param("campaignId") Long campaignId, @Param("teamId") Long teamId);
}
