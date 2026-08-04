package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.port.BuiltinVisibilityRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/** 어댑터: 빌트인 숨김 기록 포트의 JPA 구현. */
@Repository
public class JpaBuiltinVisibilityRepository implements BuiltinVisibilityRepository {

    private final HiddenBuiltinTemplateJpaRepository jpa;

    public JpaBuiltinVisibilityRepository(HiddenBuiltinTemplateJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public void hide(Long workspaceId, Long templateId) {
        if (jpa.existsByWorkspaceIdAndTemplateId(workspaceId, templateId)) {
            return;   // 멱등 — 유니크 제약이 경합의 마지막 방어선
        }
        jpa.save(new HiddenBuiltinTemplateEntity(workspaceId, templateId, Instant.now()));
    }

    @Override
    @Transactional
    public void unhide(Long workspaceId, Long templateId) {
        jpa.deleteByWorkspaceIdAndTemplateId(workspaceId, templateId);
    }

    @Override
    public Set<Long> hiddenTemplateIds(Long workspaceId) {
        return jpa.findByWorkspaceId(workspaceId).stream()
                .map(HiddenBuiltinTemplateEntity::getTemplateId)
                .collect(Collectors.toSet());
    }
}
