package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * One row of a contact's activity timeline, newest first. {@code type} is one of
 * SIGNUP, SENT, BOUNCED, SUPPRESSED_SKIP, OPENED, CLICKED, UNSUBSCRIBED, LIST_OPTOUT;
 * {@code detail} carries the type-specific extra (clicked URL, bounce reason,
 * suppression reason, opted-out list name). Campaign fields are null where the
 * event has no campaign (SIGNUP, UNSUBSCRIBED).
 */
public record ContactActivityView(
        String type,
        Instant occurredAt,
        String detail,
        Long campaignId,
        String campaignName
) {
}
