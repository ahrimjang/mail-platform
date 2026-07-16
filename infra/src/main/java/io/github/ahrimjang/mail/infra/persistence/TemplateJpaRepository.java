package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TemplateJpaRepository extends JpaRepository<TemplateEntity, Long> {

    Optional<TemplateEntity> findByBuiltinKey(String builtinKey);

    @org.springframework.data.jpa.repository.Query(
            "select t from TemplateEntity t where t.workspaceId = ?1 or t.workspaceId is null order by t.id")
    java.util.List<TemplateEntity> findVisibleToWorkspace(Long workspaceId);
}
