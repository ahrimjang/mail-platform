package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * Domain model of a reusable mail template. Subject and body may contain
 * {@code {{variable}}} placeholders rendered per contact at send time.
 * Pure POJO — no JPA / framework concerns.
 */
public class Template {

    private Long id;
    private String name;
    private String subject;
    private String htmlBody;
    private Instant createdAt;
    private Instant updatedAt;
    /** Seed key of a built-in template (null = user-authored). */
    private String builtinKey;

    public Template() {
    }

    /** Factory for a freshly authored template, before persistence. */
    public static Template create(String name, String subject, String htmlBody) {
        Template t = new Template();
        t.name = name;
        t.subject = subject;
        t.htmlBody = htmlBody;
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        return t;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getHtmlBody() {
        return htmlBody;
    }

    public void setHtmlBody(String htmlBody) {
        this.htmlBody = htmlBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getBuiltinKey() {
        return builtinKey;
    }

    public void setBuiltinKey(String builtinKey) {
        this.builtinKey = builtinKey;
    }

    public boolean isBuiltin() {
        return builtinKey != null;
    }
}
