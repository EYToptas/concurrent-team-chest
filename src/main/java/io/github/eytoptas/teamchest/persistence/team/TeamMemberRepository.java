package io.github.eytoptas.teamchest.persistence.team;

import io.github.eytoptas.teamchest.domain.team.TeamMember;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface TeamMemberRepository extends Repository<TeamMember, Long> {

    TeamMember save(TeamMember member);

    @Query("select m.teamId from TeamMember m where m.campaignId = :campaignId and m.userId = :userId")
    Optional<Long> findTeamIdByCampaignIdAndUserId(@Param("campaignId") Long campaignId, @Param("userId") String userId);

    Optional<TeamMember> findByCampaignIdAndUserId(Long campaignId, String userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE team_member SET claimed_at = :claimedAt WHERE id = :id AND claimed_at IS NULL",
            nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    int markClaimedIfUnclaimed(@Param("id") Long memberId, @Param("claimedAt") Instant claimedAt);
}
