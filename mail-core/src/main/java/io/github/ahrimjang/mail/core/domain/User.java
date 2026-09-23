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
    /** 플랫폼 운영자 권한(V35) — 테넌트 역할과 별개. OPERATOR 또는 null(일반 사용자). */
    private String platformRole;

    public static final String PLATFORM_OPERATOR = "OPERATOR";

    public User() {
    }

    /**
     * 계정 식별자로 쓰는 이메일의 정규형 — 앞뒤 공백 제거 + 소문자.
     *
     * <p>이메일의 도메인부는 대소문자를 구분하지 않고, 로컬부도 실무상 구분하는 메일 서버가
     * 사실상 없다. 반면 우리 유니크 제약·조회는 문자 그대로 비교하므로, 정규화하지 않으면
     * {@code User@x.com} 으로 가입한 사람이 {@code user@x.com} 으로 로그인하지 못하고
     * 두 주소가 서로 다른 계정으로 가입된다(모바일 자동 대문자화로 흔히 밟는 경로).
     *
     * <p><b>쓰기·조회 양쪽 모두 이걸 거쳐야 한다.</b> 한쪽만 하면 기존 행을 못 찾는다.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Factory for a freshly registered user, before persistence. */
    public static User register(String email, String passwordHash, String displayName) {
        User u = new User();
        u.email = normalizeEmail(email);
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
        u.email = normalizeEmail(email);
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

    public String getPlatformRole() {
        return platformRole;
    }

    public void setPlatformRole(String platformRole) {
        this.platformRole = platformRole;
    }

    /** 테넌트를 넘나드는 운영 화면(/ops)에 들어갈 수 있는가. */
    public boolean isPlatformOperator() {
        return PLATFORM_OPERATOR.equals(platformRole);
    }
}
