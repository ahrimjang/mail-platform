package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.AuthResponse;
import io.github.ahrimjang.mail.common.LoginRequest;
import io.github.ahrimjang.mail.common.SignupRequest;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.PasswordHasher;
import io.github.ahrimjang.mail.core.port.TokenService;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.springframework.stereotype.Service;

/**
 * Use cases for registering and authenticating users.
 *
 * <p>Signup registers a company: it creates the tenant workspace and its
 * first user as ADMIN (further members are added from the admin console).
 * Login verifies the supplied password against the stored hash. Both paths
 * return an {@link AuthResponse} carrying a freshly minted JWT plus the
 * workspace/role the console needs.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final LoginAttemptGuard attempts;
    private final EmailVerificationService verification;
    private final io.github.ahrimjang.mail.core.port.GoogleIdentityVerifier google;

    public AuthService(UserRepository users, WorkspaceRepository workspaces,
                       PasswordHasher hasher, TokenService tokens, LoginAttemptGuard attempts,
                       EmailVerificationService verification,
                       io.github.ahrimjang.mail.core.port.GoogleIdentityVerifier google) {
        this.users = users;
        this.workspaces = workspaces;
        this.hasher = hasher;
        this.tokens = tokens;
        this.attempts = attempts;
        this.verification = verification;
        this.google = google;
    }

    public AuthResponse signup(SignupRequest r) {
        if (r.email() == null || r.email().isBlank()
                || r.password() == null || r.password().isBlank()) {
            throw new IllegalArgumentException("email and password are required");
        }
        // 일회용 주소로 무료 발송량을 양산하는 계정 farming 차단 — 평판 방어의 입구
        if (DisposableEmailDomains.isDisposable(r.email())) {
            throw new IllegalArgumentException("일회용 이메일 주소로는 가입할 수 없어요. 실제 사용하는 주소를 입력해주세요.");
        }
        if (users.existsByEmail(r.email())) {
            throw new IllegalStateException("email already registered: " + r.email());
        }

        // A signup registers the company: the workspace is the tenant boundary,
        // and its first account runs it as ADMIN.
        String companyName = r.companyName() == null || r.companyName().isBlank()
                ? r.email().split("@")[0] + " 워크스페이스"
                : r.companyName().trim();
        Workspace workspace = workspaces.save(Workspace.of(companyName));

        String passwordHash = hasher.hash(r.password());
        User user = User.register(r.email(), passwordHash, r.displayName());
        user.setWorkspaceId(workspace.getId());
        user.setRole("ADMIN");
        User saved = users.save(user);
        String token = tokens.issue(saved);

        // 소유 검증 메일 — 발송 실패해도 가입은 성공(콘솔 배너의 재발송이 복구 경로)
        verification.send(saved);

        return new AuthResponse(token, r.email(), r.displayName(), workspace.getName(),
                saved.getRole(), false);
    }

    /**
     * @param clientIp 브루트포스 잠금의 IP 축 키 (프록시 뒤에서는 X-Forwarded-For 해석값)
     */
    public AuthResponse login(LoginRequest r, String clientIp) {
        // 잠긴 계정/IP 는 비밀번호 검증(BCrypt 비용)까지 가지 않고 여기서 끊는다
        attempts.checkAllowed(r.email(), clientIp);
        User user = users.findByEmail(r.email()).orElse(null);
        // 소셜 가입 계정은 비밀번호가 없다 — BCrypt 비교 전에 안내로 끊는다
        if (user != null && user.getPasswordHash() == null) {
            throw new IllegalArgumentException("이 계정은 Google 로그인으로 가입됐어요. 'Google로 계속하기'를 이용해주세요.");
        }
        if (user == null || !hasher.matches(r.password(), user.getPasswordHash())) {
            attempts.onFailure(r.email(), clientIp);
            throw new IllegalArgumentException("invalid email or password");
        }
        attempts.onSuccess(r.email());
        String workspaceName = workspaces.findById(user.getWorkspaceId())
                .map(Workspace::getName)
                .orElse(null);
        return new AuthResponse(tokens.issue(user), user.getEmail(), user.getDisplayName(),
                workspaceName, user.getRole(), user.isEmailVerified());
    }

    /**
     * 구글 로그인 — ID 토큰 검증 후 계정이 없으면 즉석 가입(워크스페이스 생성),
     * 있으면 연결 로그인. 구글이 메일함 소유를 이미 검증했으므로(email_verified)
     * 우리 인증 메일 절차를 건너뛰고 발송 게이트도 바로 열린다. 비밀번호 브루트포스
     * 잠금({@link LoginAttemptGuard})은 비밀번호 추측이 없는 이 경로와 무관.
     */
    public AuthResponse loginWithGoogle(String idToken) {
        var identity = google.verify(idToken);

        User user = users.findByEmail(identity.email()).orElse(null);
        if (user == null) {
            // 즉석 가입 — 일반 가입과 같은 구도 (워크스페이스 = 테넌트, 첫 계정 = ADMIN)
            Workspace workspace = workspaces.save(
                    Workspace.of(identity.email().split("@")[0] + " 워크스페이스"));
            User created = User.registerSocial(identity.email(), identity.displayName(),
                    "GOOGLE", identity.subject());
            created.setWorkspaceId(workspace.getId());
            created.setRole("ADMIN");
            if (identity.emailVerified()) {
                created.setEmailVerifiedAt(java.time.Instant.now());
            }
            User saved = users.save(created);
            if (!saved.isEmailVerified()) {
                verification.send(saved);   // 드문 케이스: 구글이 미검증 메일이라 답한 경우
            }
            return new AuthResponse(tokens.issue(saved), saved.getEmail(), saved.getDisplayName(),
                    workspace.getName(), saved.getRole(), saved.isEmailVerified());
        }

        // 기존 계정 연결 로그인 — 같은 메일함 소유를 구글이 검증했으므로 안전.
        boolean dirty = false;
        if (user.getProviderSubject() == null) {
            user.setProviderSubject(identity.subject());
            dirty = true;
        }
        if (!user.isEmailVerified() && identity.emailVerified()) {
            user.setEmailVerifiedAt(java.time.Instant.now());   // 구글 검증으로 우리 인증도 갈음
            dirty = true;
        }
        if (dirty) {
            users.save(user);
        }
        String workspaceName = workspaces.findById(user.getWorkspaceId())
                .map(Workspace::getName)
                .orElse(null);
        return new AuthResponse(tokens.issue(user), user.getEmail(), user.getDisplayName(),
                workspaceName, user.getRole(), user.isEmailVerified());
    }
}
