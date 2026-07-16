package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "suppressions")
public class SuppressionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant. */
    @Column(name = "workspace_id")
    private Long workspaceId;


    @Column(nullable = false, unique = true)
    private String email;

    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected SuppressionEntity() {
    }

    public SuppressionEntity(Long id, String email, String reason, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.reason = reason;
        this.createdAt = createdAt;
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


    public String getEmail() {
        return email;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
