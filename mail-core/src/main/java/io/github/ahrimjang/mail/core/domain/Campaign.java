package io.github.ahrimjang.mail.core.domain;

import io.github.ahrimjang.mail.common.CampaignStatus;

import java.time.Instant;

/**
 * Domain model of a bulk-mail campaign. Pure POJO — no JPA / framework concerns.
 */
public class Campaign {

    private Long id;
    private String subject;
    private String body;
    private CampaignStatus status;
    private Instant createdAt;

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
}
