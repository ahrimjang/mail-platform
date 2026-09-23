package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.User;

import java.util.Optional;

/**
 * Persistence port for users. Implemented by an infra adapter.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findByEmail(String email);

    /**
     * 대소문자를 무시한 조회. 가입은 입력한 철자 그대로 저장하므로, 환경변수·운영 입력처럼
     * 사람이 따로 적어 넣은 주소로 계정을 찾을 때는 이쪽을 쓴다(운영자 시더).
     */
    Optional<User> findByEmailIgnoreCase(String email);

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
