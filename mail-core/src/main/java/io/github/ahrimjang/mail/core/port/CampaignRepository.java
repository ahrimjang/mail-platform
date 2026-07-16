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

    /** Remove a campaign row entirely — only sensible for DRAFTs (no messages yet). */
    void deleteById(Long id);

    List<Campaign> findByWorkspace(Long workspaceId);

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
     * serializes concurrent schedulers so only one wins). A canceled campaign
     * is never claimable: the update also requires {@code status = QUEUED}.
     *
     * @return true if this call won the claim; false means another scheduler
     *         already released it (or it was canceled) — the caller must skip,
     *         not error.
     */
    boolean claimForEnqueue(Long id, Instant now);

    /**
     * Atomically cancels a still-deferred scheduled campaign (single conditional
     * update on {@code enqueuedAt IS NULL AND status = QUEUED}). This races the
     * scheduler's {@link #claimForEnqueue} on the same row: whichever update
     * commits first wins, so a campaign is either released or canceled — never
     * both.
     *
     * @return true if the campaign flipped to CANCELED; false means it was
     *         already released (or never deferred) and cannot be canceled.
     */
    boolean claimForCancel(Long id);

    /**
     * QUEUED -> EXPANDING atomic claim for fan-out. True if this caller won
     * (redelivered jobs lose it). Single atomic conditional UPDATE.
     */
    boolean claimForFanout(Long id);

    /**
     * EXPANDING -> SENDING once fan-out finished creating+enqueuing all messages.
     * Single atomic conditional UPDATE.
     */
    void markExpanded(Long id);

    /**
     * QUEUED -> SENDING (dispatch marking first progress on an ad-hoc campaign).
     * True if it flipped. Single atomic conditional UPDATE.
     */
    boolean markSendingIfQueued(Long id);

    /**
     * SENDING -> COMPLETED once drained. No-op while EXPANDING, so fan-out in
     * progress is never completed early. Single atomic conditional UPDATE.
     */
    void completeIfSending(Long id);

    /** Stamp when a winner-flow A/B campaign's test batch should be evaluated. */
    void scheduleAbEvaluation(Long id, Instant evaluateAt);

    /** Winner-flow campaigns due for evaluation (no winner yet, evaluate time passed). */
    List<Campaign> findDueForAbEvaluation(Instant now);

    /** Atomically claims the winner decision (single conditional UPDATE on ab_winner IS NULL). */
    boolean claimAbWinner(Long id, String winner);
}
