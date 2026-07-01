package io.github.ahrimjang.mail.common;

/**
 * Request to register a new user account.
 *
 * @param email       login email; must be unique across accounts
 * @param password    raw password; hashed before persistence
 * @param displayName human-friendly name shown in the UI
 */
public record SignupRequest(
        String email,
        String password,
        String displayName
) {
}
