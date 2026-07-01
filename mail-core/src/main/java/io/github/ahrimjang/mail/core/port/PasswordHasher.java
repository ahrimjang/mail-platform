package io.github.ahrimjang.mail.core.port;

/**
 * Hashing port for user passwords. Implemented by an infra adapter.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
