package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.User;

import java.util.Optional;

/**
 * Token port for issuing and verifying authentication tokens. Implemented by an infra adapter.
 */
public interface TokenService {

    /** Issues a signed JWT whose subject is the user's email. */
    String issue(User user);

    /** Verifies a token, returning the email subject if valid, else empty. */
    Optional<String> verify(String token);
}
