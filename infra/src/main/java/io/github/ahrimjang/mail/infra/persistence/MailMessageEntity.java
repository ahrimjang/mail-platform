package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.MessageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "mail_messages",
        indexes = {
                // Supports the worker's "claim next pending batch" query.
                @Index(name = "idx_msg_status", columnList = "status"),
                @Index(name = "idx_msg_campaign", columnList = "campaignId"),
        }
)
public class MailMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private Instant updatedAt;

    private String unsubToken;

    private String trackingToken;

    protected MailMessageEntity() {
    }

    public MailMessageEntity(Long id, Long campaignId, String recipient, MessageStatus status,
                             int attempts, String errorMessage, Instant updatedAt, String unsubToken,
                             String trackingToken) {
        this.id = id;
        this.campaignId = campaignId;
        this.recipient = recipient;
        this.status = status;
        this.attempts = attempts;
        this.errorMessage = errorMessage;
        this.updatedAt = updatedAt;
        this.unsubToken = unsubToken;
        this.trackingToken = trackingToken;
    }

    public Long getId() {
        return id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public String getRecipient() {
        return recipient;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUnsubToken() {
        return unsubToken;
    }

    public String getTrackingToken() {
        return trackingToken;
    }
}
