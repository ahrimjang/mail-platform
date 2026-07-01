package io.github.ahrimjang.mail.common;

/**
 * Normalized bounce/complaint notification accepted at the generic webhook.
 *
 * @param email     the address that bounced or complained
 * @param type      classification of the problem (permanent vs. transient)
 * @param reason    provider-supplied human-readable reason
 * @param messageId optional correlation to a specific {@code MailMessage}; may be
 *                  {@code null} when the provider cannot echo back our message id
 */
public record BounceNotification(String email, BounceType type, String reason, Long messageId) {
}
