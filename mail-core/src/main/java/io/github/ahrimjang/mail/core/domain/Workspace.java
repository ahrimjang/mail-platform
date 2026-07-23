package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * Domain model of a tenant workspace — the isolation unit every root entity
 * (campaigns, contacts, lists, templates, suppressions) belongs to. Sending
 * infrastructure (SMTP/SES, storage) is platform-owned; tenants are billed by
 * monthly send volume, so the workspace carries usage-facing settings only.
 * Pure POJO — no JPA / framework concerns.
 */
public class Workspace {

    private Long id;
    private String name;
    /** Send throttle in msgs/sec; null = unlimited. */
    private Integer sendRatePerSec;
    private Instant createdAt;

    public Workspace() {
    }

    /** Factory for a freshly registered tenant, before persistence. */
    public static Workspace of(String name) {
        Workspace w = new Workspace();
        w.name = name;
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

    public Integer getSendRatePerSec() {
        return sendRatePerSec;
    }

    public void setSendRatePerSec(Integer sendRatePerSec) {
        this.sendRatePerSec = sendRatePerSec;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
