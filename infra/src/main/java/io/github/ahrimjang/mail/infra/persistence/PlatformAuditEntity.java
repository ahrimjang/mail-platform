package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 플랫폼 운영자 감사 로그 (V35). */
@Entity
@Table(name = "platform_audit_log")
public class PlatformAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    @Column(nullable = false, length = 48)
    private String action;

    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlatformAuditEntity() {
    }

    public PlatformAuditEntity(Long id, String actorEmail, String action, Long workspaceId, Long campaignId,
                               String detail, Instant createdAt) {
        this.id = id;
        this.actorEmail = actorEmail;
        this.action = action;
        this.workspaceId = workspaceId;
        this.campaignId = campaignId;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getCampaignId() { return campaignId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
