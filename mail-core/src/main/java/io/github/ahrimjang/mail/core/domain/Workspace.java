package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * Domain model of a tenant workspace — the isolation unit every root entity
 * (campaigns, contacts, lists, templates, suppressions) belongs to. Provider
 * fields are the BYO-connector selection: which SMTP relay and file storage
 * this tenant will bring, so infra-heavy costs bill to their own account.
 * Pure POJO — no JPA / framework concerns.
 */
public class Workspace {

    /** Selectable SMTP providers (wiring comes later; MAILHOG is the dev default). */
    public static final java.util.Set<String> SMTP_PROVIDERS =
            java.util.Set.of("MAILHOG", "AWS_SES", "SENDGRID", "CUSTOM_SMTP");

    /** Selectable file-storage providers (LOCAL is the dev default). */
    public static final java.util.Set<String> STORAGE_PROVIDERS =
            java.util.Set.of("LOCAL", "AWS_S3", "NCP_OBJECT_STORAGE");

    private Long id;
    private String name;
    private String smtpProvider;
    private String storageProvider;
    private Instant createdAt;

    public Workspace() {
    }

    /** Factory for a freshly registered tenant, before persistence. */
    public static Workspace of(String name) {
        Workspace w = new Workspace();
        w.name = name;
        w.smtpProvider = "MAILHOG";
        w.storageProvider = "LOCAL";
        w.createdAt = Instant.now();
        return w;
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

    public String getSmtpProvider() {
        return smtpProvider;
    }

    public void setSmtpProvider(String smtpProvider) {
        this.smtpProvider = smtpProvider;
    }

    public String getStorageProvider() {
        return storageProvider;
    }

    public void setStorageProvider(String storageProvider) {
        this.storageProvider = storageProvider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
