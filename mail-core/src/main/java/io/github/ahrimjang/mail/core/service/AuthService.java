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

    public AuthService(UserRepository users, WorkspaceRepository workspaces,
                       PasswordHasher hasher, TokenService tokens, LoginAttemptGuard attempts) {
        this.users = users;
        this.workspaces = workspaces;
        this.hasher = hasher;
        this.tokens = tokens;
        this.attempts = attempts;
    }

    public AuthResponse signup(SignupRequest r) {
        if (r.email() == null || r.email().isBlank()
                || r.password() == null || r.password().isBlank()) {
            throw new IllegalArgumentException("email and password are required");
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

        return new AuthResponse(token, r.email(), r.displayName(), workspace.getName(), saved.getRole());
    }

    /**
     * @param clientIp 브루트포스 잠금의 IP 축 키 (프록시 뒤에서는 X-Forwarded-For 해석값)
     */
    public AuthResponse login(LoginRequest r, String clientIp) {
        // 잠긴 계정/IP 는 비밀번호 검증(BCrypt 비용)까지 가지 않고 여기서 끊는다
        attempts.checkAllowed(r.email(), clientIp);
        User user = users.findByEmail(r.email()).orElse(null);
        if (user == null || !hasher.matches(r.password(), user.getPasswordHash())) {
            attempts.onFailure(r.email(), clientIp);
            throw new IllegalArgumentException("invalid email or password");
        }
        attempts.onSuccess(r.email());
        String workspaceName = workspaces.findById(user.getWorkspaceId())
                .map(Workspace::getName)
                .orElse(null);
        return new AuthResponse(tokens.issue(user), user.getEmail(), user.getDisplayName(),
                workspaceName, user.getRole());
    }
}
