package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A recipient's own opt-out from one list — durable against membership re-adds. */
@Entity
@Table(name = "list_unsubscribes",
        uniqueConstraints = @UniqueConstraint(name = "uk_list_unsubscribes", columnNames = {"list_id", "contact_id"}))
public class ListUnsubscribeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "list_id", nullable = false)
    private Long listId;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected ListUnsubscribeEntity() {
    }

    public ListUnsubscribeEntity(Long id, Long listId, Long contactId, String reason, Instant createdAt) {
        this.id = id;
        this.listId = listId;
        this.contactId = contactId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getListId() {
        return listId;
    }

    public Long getContactId() {
        return contactId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
