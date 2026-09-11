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

    /**
     * 플랫폼 운영자 화면 — 역할이 {@code role} 인 전 테넌트 사용자, 가입순. 워크스페이스별
     * 첫 ADMIN(문의 연락처)을 뽑는 데 쓴다. 테넌트 격리의 의도적 예외.
     */
    java.util.List<User> findByRole(String role);
}
