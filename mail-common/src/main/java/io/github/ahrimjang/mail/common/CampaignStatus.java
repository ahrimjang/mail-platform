package io.github.ahrimjang.mail.common;

/**
 * Lifecycle of a bulk-mail campaign.
 *
 * <p>{@code QUEUED} -> recipients enqueued, waiting for the worker.
 * {@code SENDING} -> worker is draining the queue.
 * {@code COMPLETED} -> every message reached a terminal state (sent or failed).
 * {@code CANCELED} -> a scheduled campaign was canceled before its release
 * (only reachable while the queue publish was still deferred).
 */
public enum CampaignStatus {
    DRAFT,
    QUEUED,
    SENDING,
    COMPLETED,
    CANCELED,
}
