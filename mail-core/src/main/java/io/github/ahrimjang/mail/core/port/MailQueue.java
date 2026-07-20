package io.github.ahrimjang.mail.core.port;

/**
 * Outbound port for handing a queued message id to the send queue.
 *
 * <p>The message body/state lives in the store; only the id travels through
 * the queue. A broker adapter (RabbitMQ) implements this in the infra module.
 */
public interface MailQueue {

    /** Enqueue one send job for the given message id. */
    void enqueue(Long messageId);

    /** Enqueue a fan-out job so the worker expands a list campaign's recipients. */
    void enqueueFanout(Long campaignId);

    /**
     * Re-enqueue a throttled send job through a short delay, so a workspace at
     * its rate cap retries pacing instead of hot-looping on the send queue.
     */
    void enqueueThrottled(Long messageId);
}
