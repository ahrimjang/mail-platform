package io.github.ahrimjang.mail.common;

/**
 * Lifecycle of a bulk-mail campaign.
 *
 * <p>{@code QUEUED} -> recipients enqueued, waiting for the worker.
 * {@code EXPANDING} -> a list campaign's recipients are being fanned out into the queue by the worker.
 * {@code SENDING} -> worker is draining the queue.
 * {@code COMPLETED} -> every message reached a terminal state (sent or failed).
 * {@code CANCELED} -> a scheduled campaign was canceled before its release
 * (only reachable while the queue publish was still deferred).
 */
public enum CampaignStatus {
    DRAFT,
    QUEUED,
    EXPANDING,
    SENDING,
    COMPLETED,
    CANCELED,
}
