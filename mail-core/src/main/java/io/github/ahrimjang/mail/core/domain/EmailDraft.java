package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * 캠페인에 실제 쓰는 이메일 콘텐츠. 템플릿(재사용 디자인 자산)과 구분되는 계층 —
 * 템플릿을 불러와 만들면 내용이 복사되고(sourceTemplateId 로 계보만 남김) 이후
 * 자유롭게 수정한다. 캠페인 등록 시 내용이 캠페인으로 스냅샷되므로, 발송 후
 * 이메일을 고쳐도 과거 캠페인은 불변이다. 순수 POJO.
 */
public class EmailDraft {

    private Long id;
    private Long workspaceId;
    private String name;
    private String subject;
    private String htmlBody;
    private Long sourceTemplateId;
    private Instant createdAt;
    private Instant updatedAt;

    public static EmailDraft of(Long workspaceId, String name, String subject,
                                String htmlBody, Long sourceTemplateId) {
        EmailDraft e = new EmailDraft();
        e.workspaceId = workspaceId;
        e.name = name;
        e.subject = subject;
        e.htmlBody = htmlBody;
        e.sourceTemplateId = sourceTemplateId;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getHtmlBody() { return htmlBody; }
    public void setHtmlBody(String htmlBody) { this.htmlBody = htmlBody; }
    public Long getSourceTemplateId() { return sourceTemplateId; }
    public void setSourceTemplateId(Long sourceTemplateId) { this.sourceTemplateId = sourceTemplateId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
