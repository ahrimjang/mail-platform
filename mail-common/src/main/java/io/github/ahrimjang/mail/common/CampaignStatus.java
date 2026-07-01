package io.github.ahrimjang.mail.common;

/**
 * Lifecycle of a bulk-mail campaign.
 *
 * <p>{@code QUEUED} -> recipients enqueued, waiting for the worker.
 * {@code SENDING} -> worker is draining the queue.
 * {@code COMPLETED} -> every message reached a terminal state (sent or failed).
 */
public enum CampaignStatus {
    DRAFT,
    QUEUED,
    SENDING,
    COMPLETED,
}
