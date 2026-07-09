package io.github.ahrimjang.mail.core.domain;

import io.github.ahrimjang.mail.common.CampaignStatus;

import java.time.Instant;

/**
 * Domain model of a bulk-mail campaign. Pure POJO — no JPA / framework concerns.
 *
 * <p>{@code senderName}/{@code senderEmail} override the SMTP default From when
 * present. {@code scheduledAt} marks a deferred campaign; {@code enqueuedAt}
 * records when its messages were released to the send queue (null = not yet —
 * the worker's scheduler claims and releases due campaigns exactly once).
 */
public class Campaign {

    private Long id;
    private String subject;
    private String body;
    private CampaignStatus status;
    private Instant createdAt;
    private String senderName;
    private String senderEmail;
    private Instant scheduledAt;
    private Instant enqueuedAt;
    // Soft provenance references (content/audience snapshotted at create time):
    // kept for display even if the template or list is deleted later.
    private Long templateId;
    private Long listId;

    public Campaign() {
    }

    /** Factory for a freshly authored campaign, before persistence. */
    public static Campaign draft(String subject, String body) {
        Campaign c = new Campaign();
        c.subject = subject;
        c.body = body;
        c.status = CampaignStatus.DRAFT;
        c.createdAt = Instant.now();
        return c;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
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

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public void setEnqueuedAt(Instant enqueuedAt) {
        this.enqueuedAt = enqueuedAt;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getListId() {
        return listId;
    }

    public void setListId(Long listId) {
        this.listId = listId;
    }
}
