package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/** 콘솔 인앱 알림 — 워크스페이스 단위, read_at 이 찍히면 읽음. 순수 POJO. */
public class Notification {

    private Long id;
    private Long workspaceId;
    private String type;
    private String title;
    private Long campaignId;
    private Instant createdAt;
    private Instant readAt;

    public static Notification of(Long workspaceId, String type, String title, Long campaignId) {
        Notification n = new Notification();
        n.workspaceId = workspaceId;
        n.type = type;
        n.title = title;
        n.campaignId = campaignId;
        n.createdAt = Instant.now();
        return n;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
}
