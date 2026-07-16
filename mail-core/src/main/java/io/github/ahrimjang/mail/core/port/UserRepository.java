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

    /** Every member of one workspace (the admin console's user list). */
    java.util.List<User> findByWorkspaceId(Long workspaceId);

    /** Look a user up by primary key (role changes). */
    java.util.Optional<User> findById(Long id);

    /** Member count of a workspace. */
    long countByWorkspaceId(Long workspaceId);
}
