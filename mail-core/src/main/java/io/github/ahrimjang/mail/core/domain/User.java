package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * Domain model of a registered user. Pure POJO — no JPA / framework concerns.
 */
public class User {

    private Long id;
    private Long workspaceId; // the workspace this account belongs to
    private String role;   // ADMIN (runs the workspace) or OPERATOR (runs campaigns)
    private String email;
    private String passwordHash;
    private String displayName;
    private Instant createdAt;
    private Instant emailVerifiedAt;   // 가입 이메일 소유 검증 완료 시각 (null = 미인증)
    private String authProvider = "LOCAL";   // 가입 경로: LOCAL(이메일+비밀번호) | GOOGLE
    private String providerSubject;          // IdP 발급 고유 식별자 (구글 sub) — 소셜 연결 시에만

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

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    /** 가입 이메일 소유 검증을 마쳤는지 — 발송 경로의 게이트 조건. */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /** 소셜 가입 팩토리 — 비밀번호 없이 IdP 신원으로 만든다. */
    public static User registerSocial(String email, String displayName,
                                      String provider, String providerSubject) {
        User u = new User();
        u.email = email;
        u.displayName = displayName;
        u.authProvider = provider;
        u.providerSubject = providerSubject;
        u.createdAt = Instant.now();
        return u;
    }

    public String getAuthProvider() {
        return authProvider;
    }

    public void setAuthProvider(String authProvider) {
        this.authProvider = authProvider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public void setProviderSubject(String providerSubject) {
        this.providerSubject = providerSubject;
    }
}
