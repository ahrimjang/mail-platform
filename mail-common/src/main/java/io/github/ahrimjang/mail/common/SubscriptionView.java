package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * Read model of a contact's subscription state, derived from the suppression
 * list (the platform's single do-not-send source of truth).
 *
 * @param suppressed whether the contact's address is on the suppression list
 * @param reason     why it was suppressed (e.g. "unsubscribe", "manual"), or null
 * @param since      when the suppression was recorded, or null
 */
public record SubscriptionView(
        boolean suppressed,
        String reason,
        Instant since
) {
}
