package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.AuthResponse;
import io.github.ahrimjang.mail.common.LoginRequest;
import io.github.ahrimjang.mail.common.SignupRequest;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.PasswordHasher;
import io.github.ahrimjang.mail.core.port.TokenService;
import io.github.ahrimjang.mail.core.port.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Use cases for registering and authenticating users.
 *
 * <p>Signup hashes the raw password before persistence and issues a token on
 * success; login verifies the supplied password against the stored hash. Both
 * paths return an {@link AuthResponse} carrying a freshly minted JWT.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final TokenService tokens;

    public AuthService(UserRepository users, PasswordHasher hasher, TokenService tokens) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
    }

    public AuthResponse signup(SignupRequest r) {
        if (r.email() == null || r.email().isBlank()
                || r.password() == null || r.password().isBlank()) {
            throw new IllegalArgumentException("email and password are required");
        }
        if (users.existsByEmail(r.email())) {
            throw new IllegalStateException("email already registered: " + r.email());
        }

        String passwordHash = hasher.hash(r.password());
        User user = User.register(r.email(), passwordHash, r.displayName());
        User saved = users.save(user);
        String token = tokens.issue(saved);

        return new AuthResponse(token, r.email(), r.displayName());
    }

    public AuthResponse login(LoginRequest r) {
        User user = users.findByEmail(r.email()).orElse(null);
        if (user == null || !hasher.matches(r.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("invalid email or password");
        }
        return new AuthResponse(tokens.issue(user), user.getEmail(), user.getDisplayName());
    }
}
