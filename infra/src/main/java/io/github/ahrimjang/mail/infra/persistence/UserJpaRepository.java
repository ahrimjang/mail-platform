package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    java.util.List<UserEntity> findByWorkspaceIdOrderByCreatedAtAsc(Long workspaceId);

    long countByWorkspaceId(Long workspaceId);

    /** 플랫폼 운영자 화면 — 역할별 전 테넌트 사용자, 가입순. */
    java.util.List<UserEntity> findByRoleOrderByCreatedAtAsc(String role);
}
