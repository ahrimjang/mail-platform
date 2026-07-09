package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TemplateJpaRepository extends JpaRepository<TemplateEntity, Long> {

    Optional<TemplateEntity> findByBuiltinKey(String builtinKey);
}
