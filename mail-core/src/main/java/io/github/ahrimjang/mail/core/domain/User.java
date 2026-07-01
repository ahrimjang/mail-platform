package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * Domain model of a registered user. Pure POJO — no JPA / framework concerns.
 */
public class User {

    private Long id;
    private String email;
    private String passwordHash;
    private String displayName;
    private Instant createdAt;

    public User() {
    }

    /** Factory for a freshly registered user, before persistence. */
    public static User register(String email, String passwordHash, String displayName) {
        User u = new User();
        u.email = email;
        u.passwordHash = passwordHash;
        u.displayName = displayName;
        u.createdAt = Instant.now();
        return u;
    }

    public Long getId() {
        return id;
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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
