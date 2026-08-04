package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;
import java.util.UUID;

/** 가입 이메일 인증 토큰 — 1회용, 24시간 만료. 순수 POJO. */
public class EmailVerificationToken {

    public static final java.time.Duration TTL = java.time.Duration.ofHours(24);

    private Long id;
    private Long userId;
    private String token;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt;

    public static EmailVerificationToken issue(Long userId) {
        EmailVerificationToken t = new EmailVerificationToken();
        t.userId = userId;
        t.token = UUID.randomUUID().toString();
        t.createdAt = Instant.now();
        t.expiresAt = t.createdAt.plus(TTL);
        return t;
    }

    /** 확인 시점 유효성 — 미사용이면서 만료 전. */
    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
