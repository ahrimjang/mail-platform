package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "list_memberships",
        uniqueConstraints = @UniqueConstraint(columnNames = {"listId", "contactId"})
)
public class ListMembershipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long listId;

    @Column(nullable = false)
    private Long contactId;

    protected ListMembershipEntity() {
    }

    public ListMembershipEntity(Long id, Long listId, Long contactId) {
        this.id = id;
        this.listId = listId;
        this.contactId = contactId;
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
}
