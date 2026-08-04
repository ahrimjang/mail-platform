package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 이메일 — 캠페인용 콘텐츠 계층 (V27). 장문 본문은 text 컬럼(@Lob 금지 규칙). */
@Entity
@Table(name = "emails")
public class EmailDraftEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String subject;

    @Column(name = "html_body", nullable = false, columnDefinition = "text")
    private String htmlBody;

    @Column(name = "source_template_id")
    private Long sourceTemplateId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmailDraftEntity() {
    }

    public EmailDraftEntity(Long id, Long workspaceId, String name, String subject, String htmlBody,
                            Long sourceTemplateId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.name = name;
        this.subject = subject;
        this.htmlBody = htmlBody;
        this.sourceTemplateId = sourceTemplateId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getSubject() { return subject; }
    public String getHtmlBody() { return htmlBody; }
    public Long getSourceTemplateId() { return sourceTemplateId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
