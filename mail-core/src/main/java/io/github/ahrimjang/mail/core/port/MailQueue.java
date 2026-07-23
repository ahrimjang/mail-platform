package io.github.ahrimjang.mail.core.port;

/**
 * Outbound port for handing a queued message id to the send queue.
 *
 * <p>The message body/state lives in the store; only the id travels through
 * the queue. A broker adapter (RabbitMQ) implements this in the infra module.
 */
public interface MailQueue {
    // Methods are ordered by a list campaign's lifecycle:
    // one fan-out job -> N send jobs -> (only when rate-capped) throttled re-enqueues.

    /** Enqueue a fan-out job so the worker expands a list campaign's recipients. */
    void enqueueFanout(Long campaignId);

    /** Enqueue one send job for the given message id. */
    void enqueue(Long messageId);

    /**
     * Re-enqueue a throttled send job through a short delay, so a workspace at
     * its rate cap retries pacing instead of hot-looping on the send queue.
     */
    void enqueueThrottled(Long messageId);
}
