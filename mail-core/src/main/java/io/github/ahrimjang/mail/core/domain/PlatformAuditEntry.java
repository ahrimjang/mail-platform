package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * 플랫폼 운영자 감사 기록 — 테넌트 격리의 의도적 예외인 /ops 조치를 전부 남긴다.
 * 순수 POJO.
 */
public class PlatformAuditEntry {

    public static final String SUSPEND = "SUSPEND";
    public static final String UNSUSPEND = "UNSUSPEND";
    public static final String CHANGE_PLAN = "CHANGE_PLAN";
    public static final String REVOKE_API_KEY = "REVOKE_API_KEY";
    public static final String ABORT_CAMPAIGN = "ABORT_CAMPAIGN";

    private Long id;
    private String actorEmail;
    private String action;
    private Long workspaceId;
    private Long campaignId;
    private String detail;
    private Instant createdAt;

    public static PlatformAuditEntry of(String actorEmail, String action, Long workspaceId,
                                        Long campaignId, String detail) {
        PlatformAuditEntry e = new PlatformAuditEntry();
        e.actorEmail = actorEmail;
        e.action = action;
        e.workspaceId = workspaceId;
        e.campaignId = campaignId;
        e.detail = detail;
        e.createdAt = Instant.now();
        return e;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
