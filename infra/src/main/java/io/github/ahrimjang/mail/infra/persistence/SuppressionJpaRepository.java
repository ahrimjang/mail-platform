package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SuppressionJpaRepository extends JpaRepository<SuppressionEntity, Long> {

    boolean existsByEmail(String email);
}
