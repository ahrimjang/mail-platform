package io.github.ahrimjang.mail.common;

/**
 * One engagement event on the Kafka event stream (open / click / bounce).
 *
 * <p>Wire payload for the {@code mail.events} topic: produced by mail-api when a
 * recipient engages (or a bounce is correlated), consumed by mail-worker which
 * projects it into the {@code email_events} read model. {@code occurredAt} is an
 * epoch-millisecond timestamp (not {@code Instant}) so JSON (de)serialization needs
 * no JavaTime module configuration on either side.
 */
public record EmailEventMessage(
        Long messageId,
        Long campaignId,
        EventType type,
        String url,
        long occurredAtEpochMilli) {
}
