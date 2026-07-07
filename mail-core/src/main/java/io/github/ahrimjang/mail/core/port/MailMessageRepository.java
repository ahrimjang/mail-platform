package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.MailMessage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the per-recipient send queue.
 */
public interface MailMessageRepository {

    /** Bulk-insert freshly enqueued messages, returning the saved rows. */
    List<MailMessage> saveAll(List<MailMessage> messages);

    /** Persist a single message after a state change (sent/failed). */
    MailMessage save(MailMessage message);

    /** Look up a message by its id. */
    Optional<MailMessage> findById(Long id);

    /**
     * Atomically claims a message for processing by flipping PENDING -&gt; SENDING
     * (single conditional update; the database serializes concurrent callers so
     * only one wins). A message already SENDING for longer than {@code staleAfter}
     * is also reclaimable — that implies whoever claimed it crashed before
     * finishing, so a redelivered queue job must still be able to pick it back up.
     *
     * @return true if this call won the claim; false if the message is missing or
     *         is being actively processed by another consumer right now — the
     *         caller must treat false as a safe no-op, not an error.
     */
    boolean claim(Long messageId, Duration staleAfter);

    /** Aggregate per-status counts for one campaign. */
    MessageCounts countByCampaign(Long campaignId);

    /** Ids of a campaign's PENDING messages — what a scheduled release enqueues. */
    List<Long> findPendingIdsByCampaign(Long campaignId);

    /** Most recently updated messages of a campaign (per-recipient drill-down), newest first. */
    List<MailMessage> findRecentByCampaign(Long campaignId, int limit);

    /**
     * Send log aggregated into fixed time buckets: one row per (bucket, status)
     * with a count, newest bucket first. The database does the grouping so the
     * feed stays bounded no matter how many recipients a campaign has.
     */
    List<SendLogBucket> aggregateLogByCampaign(Long campaignId, int bucketSeconds, int limit);

    /** One aggregated bucket row; {@code sampleError} carries a representative failure reason. */
    record SendLogBucket(java.time.Instant bucketStart, io.github.ahrimjang.mail.common.MessageStatus status,
                         long count, String sampleError) {
    }

    /** Look up a message by its per-message unsubscribe token. */
    Optional<MailMessage> findByUnsubToken(String token);

    /** Look up a message by its per-message tracking token. */
    Optional<MailMessage> findByTrackingToken(String trackingToken);

    /** Snapshot of delivery progress for a campaign. SENDING messages are in-flight, not yet terminal. */
    record MessageCounts(long total, long pending, long sending, long sent, long failed, long bounced, long suppressed) {
    }
}
