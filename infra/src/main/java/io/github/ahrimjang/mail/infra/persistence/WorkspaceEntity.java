package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workspaces")
public class WorkspaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Send throttle in msgs/sec; null = unlimited. */
    @Column
    private Integer sendRatePerSec;

    @Column(nullable = false)
    private Instant createdAt;

    protected WorkspaceEntity() {
    }

    public WorkspaceEntity(Long id, String name, Integer sendRatePerSec, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.sendRatePerSec = sendRatePerSec;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getSendRatePerSec() {
        return sendRatePerSec;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
