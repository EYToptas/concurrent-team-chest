package io.github.eytoptas.teamchest.persistence.campaign;

import io.github.eytoptas.teamchest.domain.campaign.Campaign;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface CampaignRepository extends Repository<Campaign, Long> {

    Campaign save(Campaign campaign);

    Optional<Campaign> findById(Long id);

    List<Campaign> findByEndsAtAfter(Instant endsAfter);
}
