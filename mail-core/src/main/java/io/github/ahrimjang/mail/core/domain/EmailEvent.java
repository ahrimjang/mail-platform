package io.github.ahrimjang.mail.core.domain;

import io.github.ahrimjang.mail.common.EventType;

import java.time.Instant;

/**
 * A recorded recipient engagement (open or click) tied to a delivered message.
 * Engagement is event-derived and kept separate from delivery status.
 * Pure POJO — no JPA concerns.
 */
public class EmailEvent {

    private Long id;
    private Long messageId;
    private Long campaignId;
    private EventType type;
    private String url;
    private Instant occurredAt;

    public EmailEvent() {
    }

    /** Factory for a newly observed engagement event. */
    public static EmailEvent of(Long messageId, Long campaignId, EventType type, String url) {
        EmailEvent e = new EmailEvent();
        e.messageId = messageId;
        e.campaignId = campaignId;
        e.type = type;
        e.url = url;
        e.occurredAt = Instant.now();
        return e;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Long campaignId) {
        this.campaignId = campaignId;
    }

    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
