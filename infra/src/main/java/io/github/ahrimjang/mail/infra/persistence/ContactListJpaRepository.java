package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactListJpaRepository extends JpaRepository<ContactListEntity, Long> {

    java.util.List<ContactListEntity> findByWorkspaceIdOrderById(Long workspaceId);

}
