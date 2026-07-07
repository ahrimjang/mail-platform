package io.github.ahrimjang.mail.common;

/**
 * Request to change a contact's subscription state.
 *
 * @param suppressed true to add the contact's address to the suppression list,
 *                   false to remove it
 */
public record UpdateSubscriptionRequest(
        boolean suppressed
) {
}
