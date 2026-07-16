package io.github.ahrimjang.mail.common;

/**
 * Result of a successful signup or login.
 *
 * @param token       signed JWT to send as a Bearer credential
 * @param email       the authenticated user's email
 * @param displayName the authenticated user's display name
 */
public record AuthResponse(
        String token,
        String email,
        String displayName,
        String workspaceName,
        String role
) {
}
