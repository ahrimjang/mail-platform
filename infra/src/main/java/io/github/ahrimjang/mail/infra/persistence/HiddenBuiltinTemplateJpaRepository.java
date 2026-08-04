package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HiddenBuiltinTemplateJpaRepository extends JpaRepository<HiddenBuiltinTemplateEntity, Long> {

    List<HiddenBuiltinTemplateEntity> findByWorkspaceId(Long workspaceId);

    boolean existsByWorkspaceIdAndTemplateId(Long workspaceId, Long templateId);

    void deleteByWorkspaceIdAndTemplateId(Long workspaceId, Long templateId);
}
