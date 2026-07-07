package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "email_events")
public class EmailEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long messageId;

    @Column(nullable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private EventType type;

    @Column(columnDefinition = "text")
    private String url;

    @Column(nullable = false)
    private Instant occurredAt;

    protected EmailEventEntity() {
    }

    public EmailEventEntity(Long id, Long messageId, Long campaignId, EventType type,
                            String url, Instant occurredAt) {
        this.id = id;
        this.messageId = messageId;
        this.campaignId = campaignId;
        this.type = type;
        this.url = url;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public EventType getType() {
        return type;
    }

    public String getUrl() {
        return url;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
