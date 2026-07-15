package io.github.ahrimjang.mail.common;

import java.time.Instant;

/** One delivery to this contact: which campaign, how it ended, when. */
public record ContactMessageView(
        Long messageId,
        Long campaignId,
        String campaignName,
        MessageStatus status,
        Instant updatedAt
) {
}
