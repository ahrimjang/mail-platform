package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SuppressionJpaRepository extends JpaRepository<SuppressionEntity, Long> {

    boolean existsByWorkspaceIdAndEmail(Long workspaceId, String email);

    Optional<SuppressionEntity> findByWorkspaceIdAndEmail(Long workspaceId, String email);

    void deleteByWorkspaceIdAndEmail(Long workspaceId, String email);

    long countByWorkspaceId(Long workspaceId);

    @Query("select s.email from SuppressionEntity s where s.workspaceId = ?1 and s.email in ?2")
    java.util.List<String> findSuppressedEmails(Long workspaceId, java.util.Collection<String> emails);

    @Query("select s.reason, count(s) from SuppressionEntity s where s.workspaceId = ?1 "
            + "group by s.reason order by count(s) desc")
    java.util.List<Object[]> countByReason(Long workspaceId);

    @Query("select s.reason, count(s) from SuppressionEntity s where s.workspaceId = ?1 and s.createdAt >= ?2 "
            + "group by s.reason order by count(s) desc")
    java.util.List<Object[]> countByReasonSince(Long workspaceId, java.time.Instant since);
}
