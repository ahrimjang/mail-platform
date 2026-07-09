package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.CampaignStatus;
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
@Table(name = "campaigns")
public class CampaignEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    /** Optional From display name (null = SMTP default). */
    private String senderName;

    /** Optional From address (null = SMTP default). */
    private String senderEmail;

    /** Requested send time; null = immediate. */
    private Instant scheduledAt;

    /** When messages were released to the queue; null = awaiting the scheduler. */
    private Instant enqueuedAt;

    /** Template the content was snapshotted from; null = authored directly. */
    private Long templateId;

    /** Contact list the recipients were fanned out from; null = raw addresses. */
    private Long listId;

    protected CampaignEntity() {
    }

    public CampaignEntity(Long id, String subject, String body, CampaignStatus status, Instant createdAt,
                          String senderName, String senderEmail, Instant scheduledAt, Instant enqueuedAt,
                          Long templateId, Long listId) {
        this.id = id;
        this.subject = subject;
        this.body = body;
        this.status = status;
        this.createdAt = createdAt;
        this.senderName = senderName;
        this.senderEmail = senderEmail;
        this.scheduledAt = scheduledAt;
        this.enqueuedAt = enqueuedAt;
        this.templateId = templateId;
        this.listId = listId;
    }

    public Long getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public void setStatus(CampaignStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public Long getListId() {
        return listId;
    }
}
