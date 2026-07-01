package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for campaigns. Implemented by an infra adapter.
 */
public interface CampaignRepository {

    Campaign save(Campaign campaign);

    Optional<Campaign> findById(Long id);

    List<Campaign> findAll();

    void updateStatus(Long id, CampaignStatus status);
}
