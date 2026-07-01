package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.User;

import java.util.Optional;

/**
 * Persistence port for users. Implemented by an infra adapter.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
