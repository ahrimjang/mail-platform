package io.github.ahrimjang.mail.core.domain;

import io.github.ahrimjang.mail.common.MessageStatus;

import java.time.Instant;

/**
 * A single queued delivery: one campaign body addressed to one recipient.
 * This is the unit of work the worker drains from the queue.
 */
public class MailMessage {

    private Long id;
    private Long campaignId;
    private String recipient;
    private MessageStatus status;
    private int attempts;
    private String errorMessage;
    private String unsubToken;
    private String trackingToken;
    private Long contactId;
    private Instant updatedAt;
    /** A/B variant this delivery renders ("A"/"B"); null for non-A/B campaigns. */
    private String variant;

    public MailMessage() {
    }

    /** Factory for a newly enqueued, not-yet-sent message with no contact link. */
    public static MailMessage queued(Long campaignId, String recipient) {
        return queued(campaignId, recipient, null);
    }

    /** Factory for a newly enqueued, not-yet-sent message linked to a contact for personalization. */
    public static MailMessage queued(Long campaignId, String recipient, Long contactId) {
        MailMessage m = new MailMessage();
        m.campaignId = campaignId;
        m.recipient = recipient;
        m.status = MessageStatus.PENDING;
        m.attempts = 0;
        m.unsubToken = java.util.UUID.randomUUID().toString();
        m.trackingToken = java.util.UUID.randomUUID().toString();
        m.contactId = contactId;
        m.updatedAt = Instant.now();
        return m;
    }

    public void markSent() {
        this.status = MessageStatus.SENT;
        this.attempts++;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = MessageStatus.FAILED;
        this.attempts++;
        this.errorMessage = error;
        this.updatedAt = Instant.now();
    }

    public void markBounced(String error) {
        this.status = MessageStatus.BOUNCED;
        this.attempts++;
        this.errorMessage = error;
        this.updatedAt = Instant.now();
    }

    public void markSuppressed() {
        this.status = MessageStatus.SUPPRESSED;
        this.attempts++;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Long campaignId) {
        this.campaignId = campaignId;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getUnsubToken() {
        return unsubToken;
    }

    public void setUnsubToken(String unsubToken) {
        this.unsubToken = unsubToken;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public Long getContactId() {
        return contactId;
    }

    public void setContactId(Long contactId) {
        this.contactId = contactId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getVariant() {
        return variant;
    }

    public void setVariant(String variant) {
        this.variant = variant;
    }
}
