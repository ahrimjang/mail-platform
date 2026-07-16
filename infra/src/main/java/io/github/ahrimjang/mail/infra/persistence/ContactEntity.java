package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "contacts")
public class ContactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant. */
    @Column(name = "workspace_id")
    private Long workspaceId;


    @Column(nullable = false, unique = true)
    private String email;

    private String firstName;

    private String lastName;

    /** Free-form contact attributes serialized as a JSON object string. */
    @Column(columnDefinition = "text")
    private String attributesJson;

    @Column(nullable = false)
    private Instant createdAt;

    protected ContactEntity() {
    }

    public ContactEntity(Long id, String email, String firstName, String lastName,
                         String attributesJson, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.attributesJson = attributesJson;
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

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getAttributesJson() {
        return attributesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
