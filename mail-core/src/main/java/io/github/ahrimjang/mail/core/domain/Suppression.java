package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * A globally suppressed address: once present, no campaign will send to it.
 * Populated by unsubscribe requests and hard bounces. Pure POJO — no JPA concerns.
 */
public class Suppression {

    private String email;
    private String reason;
    private Instant createdAt;

    public Suppression() {
    }

    /** Factory for a newly suppressed address. */
    public static Suppression of(String email, String reason) {
        Suppression s = new Suppression();
        s.email = email;
        s.reason = reason;
        s.createdAt = Instant.now();
        return s;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
