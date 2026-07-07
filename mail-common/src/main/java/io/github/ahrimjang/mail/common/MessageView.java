package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * Read model of one per-recipient delivery, for the campaign detail send log.
 * {@code updatedAt} is the moment of the last state change (queued/sent/failed...).
 */
public record MessageView(
        Long id,
        String recipient,
        MessageStatus status,
        String errorMessage,
        Instant updatedAt
) {
}
