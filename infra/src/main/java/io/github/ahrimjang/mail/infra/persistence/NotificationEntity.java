package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 콘솔 인앱 알림 (V31). */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected NotificationEntity() {
    }

    public NotificationEntity(Long id, Long workspaceId, String type, String title,
                              Long campaignId, Instant createdAt, Instant readAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.type = type;
        this.title = title;
        this.campaignId = campaignId;
        this.createdAt = createdAt;
        this.readAt = readAt;
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public Long getCampaignId() { return campaignId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
}
