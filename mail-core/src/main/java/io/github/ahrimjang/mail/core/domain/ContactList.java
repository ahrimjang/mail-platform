package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * Domain model of a named group of contacts that campaigns can target.
 * Membership itself lives behind the repository port. Pure POJO — no JPA concerns.
 */
public class ContactList {

    private Long id;
    private Long workspaceId; // owning tenant
    private String name;
    private String description;
    private Instant createdAt;

    public ContactList() {
    }

    /** Factory for a freshly created list, before persistence. */
    public static ContactList of(String name, String description) {
        ContactList l = new ContactList();
        l.name = name;
        l.description = description;
        l.createdAt = Instant.now();
        return l;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
