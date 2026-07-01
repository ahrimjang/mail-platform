package io.github.ahrimjang.mail.common;

/**
 * Per-recipient delivery state within a campaign.
 */
public enum MessageStatus {
    PENDING,
    SENT,
    FAILED,
    BOUNCED,
    SUPPRESSED,
}
