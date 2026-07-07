package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;

import java.time.Instant;
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

    /**
     * Scheduled campaigns whose send time has arrived but whose messages have
     * not been released to the queue yet ({@code enqueuedAt} is null and
     * {@code scheduledAt <= now}).
     */
    List<Campaign> findDueForEnqueue(Instant now);

    /**
     * Atomically claims a due campaign for release by stamping {@code enqueuedAt}
     * (single conditional update on {@code enqueuedAt IS NULL} — the database
     * serializes concurrent schedulers so only one wins).
     *
     * @return true if this call won the claim; false means another scheduler
     *         already released it — the caller must skip, not error.
     */
    boolean claimForEnqueue(Long id, Instant now);
}
