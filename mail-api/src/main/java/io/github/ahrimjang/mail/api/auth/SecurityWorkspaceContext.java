package io.github.ahrimjang.mail.api.auth;

import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Adapter: resolves the acting user's workspace and role from the request's
 * security context (populated by {@link JwtAuthFilter}). The JWT keeps only
 * the email subject — workspace/role come fresh from the DB on each call, so
 * a role change takes effect without re-issuing tokens.
 */
@Component
public class SecurityWorkspaceContext implements WorkspaceContext {

    private final UserRepository users;

    public SecurityWorkspaceContext(UserRepository users) {
        this.users = users;
    }

    @Override
    public Long currentWorkspaceId() {
        return currentUser().getWorkspaceId();
    }

    @Override
    public boolean isAdmin() {
        return "ADMIN".equals(currentUser().getRole());
    }

    @Override
    public String currentUserEmail() {
        return authenticatedEmail();
    }

    private User currentUser() {
        return users.findByEmail(authenticatedEmail())
                .orElseThrow(() -> new IllegalStateException("authenticated user no longer exists"));
    }

    private String authenticatedEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("no authenticated request context");
        }
        return String.valueOf(auth.getPrincipal());
    }
}
