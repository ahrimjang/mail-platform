package io.github.ahrimjang.mail.common;

/**
 * Per-recipient delivery state within a campaign.
 */
public enum MessageStatus {
    PENDING,
    /** Claimed by a consumer and actively being sent (see MailMessageRepository#claim). */
    SENDING,
    SENT,
    FAILED,
    BOUNCED,
    SUPPRESSED,
    /** The campaign's schedule was canceled before this message was ever published. */
    CANCELED,
}
