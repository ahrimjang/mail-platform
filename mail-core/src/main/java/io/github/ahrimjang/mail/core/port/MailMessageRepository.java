package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.MailMessage;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the per-recipient send queue.
 */
public interface MailMessageRepository {

    /** Bulk-insert freshly enqueued messages. */
    void saveAll(List<MailMessage> messages);

    /** Persist a single message after a state change (sent/failed). */
    MailMessage save(MailMessage message);

    /** Claim up to {@code limit} pending messages for delivery, oldest first. */
    List<MailMessage> findPending(int limit);

    /** Aggregate per-status counts for one campaign. */
    MessageCounts countByCampaign(Long campaignId);

    /** Look up a message by its per-message unsubscribe token. */
    Optional<MailMessage> findByUnsubToken(String token);

    /** Look up a message by its per-message tracking token. */
    Optional<MailMessage> findByTrackingToken(String trackingToken);

    /** Snapshot of delivery progress for a campaign. */
    record MessageCounts(long total, long pending, long sent, long failed, long bounced, long suppressed) {
    }
}
