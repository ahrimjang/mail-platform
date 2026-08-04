package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailDraftJpaRepository extends JpaRepository<EmailDraftEntity, Long> {

    List<EmailDraftEntity> findByWorkspaceIdOrderByUpdatedAtDesc(Long workspaceId);
}
