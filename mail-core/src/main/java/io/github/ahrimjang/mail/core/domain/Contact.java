package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Domain model of an addressable contact. Deliberately has no status field —
 * the suppression list remains the single do-not-send source of truth.
 * Pure POJO — no JPA / framework concerns.
 */
public class Contact {

    private Long id;
    private Long workspaceId; // owning tenant
    private String email;
    private String firstName;
    private String lastName;
    private Map<String, String> attributes;
    private Instant createdAt;

    public Contact() {
    }

    /** Factory for a freshly captured contact, before persistence. */
    public static Contact of(String email, String firstName, String lastName, Map<String, String> attributes) {
        Contact c = new Contact();
        c.email = email;
        c.firstName = firstName;
        c.lastName = lastName;
        c.attributes = attributes == null ? new HashMap<>() : attributes;
        c.createdAt = Instant.now();
        return c;
    }

    /**
     * Flatten this contact into the variable map used for template rendering:
     * custom attributes plus the well-known {@code email}/{@code firstName}/{@code lastName} keys.
     */
    public Map<String, String> toVariables() {
        Map<String, String> vars = new HashMap<>(attributes == null ? Map.of() : attributes);
        vars.put("email", email);
        if (firstName != null) {
            vars.put("firstName", firstName);
        }
        if (lastName != null) {
            vars.put("lastName", lastName);
        }
        return vars;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
