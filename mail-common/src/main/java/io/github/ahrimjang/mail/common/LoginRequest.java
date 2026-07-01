package io.github.ahrimjang.mail.common;

/**
 * Request to authenticate an existing user.
 *
 * @param email    login email
 * @param password raw password to verify against the stored hash
 */
public record LoginRequest(
        String email,
        String password
) {
}
