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

    /** 억제 목록 페이지 — 연락처 검색과 같은 빈 문자열 센티널(q)·null 전체(reason) 규약. */
    @Query("select s from SuppressionEntity s where s.workspaceId = :ws "
            + "and (:q = '' or lower(s.email) like concat('%', lower(:q), '%')) "
            + "and (:reason is null or s.reason = :reason) "
            + "order by s.createdAt desc, s.id desc")
    java.util.List<SuppressionEntity> search(@org.springframework.data.repository.query.Param("ws") Long workspaceId,
                                             @org.springframework.data.repository.query.Param("q") String q,
                                             @org.springframework.data.repository.query.Param("reason") String reason,
                                             org.springframework.data.domain.Pageable pageable);

    @Query("select count(s) from SuppressionEntity s where s.workspaceId = :ws "
            + "and (:q = '' or lower(s.email) like concat('%', lower(:q), '%')) "
            + "and (:reason is null or s.reason = :reason)")
    long countSearch(@org.springframework.data.repository.query.Param("ws") Long workspaceId,
                     @org.springframework.data.repository.query.Param("q") String q,
                     @org.springframework.data.repository.query.Param("reason") String reason);
}
