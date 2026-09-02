package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant. */
    @Column(name = "workspace_id")
    private Long workspaceId;

    /** ADMIN or OPERATOR. */
    @Column(length = 16)
    private String role;


    @Column(nullable = false, unique = true)
    private String email;

    /** 소셜 가입자는 비밀번호가 없다 — V26 이 DB 제약도 nullable 로 풀었다. */
    private String passwordHash;

    private String displayName;

    @Column(nullable = false)
    private Instant createdAt;

    /** 가입 이메일 인증 완료 시각 (null = 미인증) */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** 가입 경로: LOCAL | GOOGLE */
    @Column(name = "auth_provider", length = 16, nullable = false)
    private String authProvider = "LOCAL";

    /** IdP 발급 고유 식별자 (구글 sub) — 소셜 연결 시에만 */
    @Column(name = "provider_subject")
    private String providerSubject;

    protected UserEntity() {
    }

    public UserEntity(Long id, String email, String passwordHash, String displayName, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }


    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
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
