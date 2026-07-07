package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SuppressionJpaRepository extends JpaRepository<SuppressionEntity, Long> {

    boolean existsByEmail(String email);

    Optional<SuppressionEntity> findByEmail(String email);

    void deleteByEmail(String email);
}
