package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 워크스페이스별 빌트인 템플릿 숨김 기록 (V29). */
@Entity
@Table(name = "hidden_builtin_templates")
public class HiddenBuiltinTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HiddenBuiltinTemplateEntity() {
    }

    public HiddenBuiltinTemplateEntity(Long workspaceId, Long templateId, Instant createdAt) {
        this.workspaceId = workspaceId;
        this.templateId = templateId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getTemplateId() { return templateId; }
    public Instant getCreatedAt() { return createdAt; }
}
