package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ContactJpaRepository extends JpaRepository<ContactEntity, Long> {

    Optional<ContactEntity> findByWorkspaceIdAndEmail(Long workspaceId, String email);

    boolean existsByWorkspaceIdAndEmail(Long workspaceId, String email);

    List<ContactEntity> findByWorkspaceIdOrderById(Long workspaceId);

    long countByWorkspaceId(Long workspaceId);

    /**
     * Filtered page of a workspace's contacts. The blank-string sentinel for
     * {@code q} sidesteps null-parameter typing; membership and suppression
     * filters are EXISTS subqueries so the row set stays a plain contact page.
     */
    @Query("select c from ContactEntity c where c.workspaceId = :ws "
            + "and (:q = '' or lower(c.email) like concat('%', lower(:q), '%') "
            + "     or lower(concat(coalesce(c.lastName, ''), coalesce(c.firstName, ''))) like concat('%', lower(:q), '%')) "
            + "and (:listId is null or exists (select 1 from ListMembershipEntity m where m.listId = :listId and m.contactId = c.id)) "
            + "and (:suppressed is null "
            + "     or (:suppressed = true and exists (select 1 from SuppressionEntity s where s.workspaceId = :ws and s.email = c.email)) "
            + "     or (:suppressed = false and not exists (select 1 from SuppressionEntity s where s.workspaceId = :ws and s.email = c.email))) "
            + "order by c.id")
    List<ContactEntity> search(@org.springframework.data.repository.query.Param("ws") Long workspaceId,
                               @org.springframework.data.repository.query.Param("q") String q,
                               @org.springframework.data.repository.query.Param("listId") Long listId,
                               @org.springframework.data.repository.query.Param("suppressed") Boolean suppressed,
                               Pageable pageable);

    @Query("select count(c) from ContactEntity c where c.workspaceId = :ws "
            + "and (:q = '' or lower(c.email) like concat('%', lower(:q), '%') "
            + "     or lower(concat(coalesce(c.lastName, ''), coalesce(c.firstName, ''))) like concat('%', lower(:q), '%')) "
            + "and (:listId is null or exists (select 1 from ListMembershipEntity m where m.listId = :listId and m.contactId = c.id)) "
            + "and (:suppressed is null "
            + "     or (:suppressed = true and exists (select 1 from SuppressionEntity s where s.workspaceId = :ws and s.email = c.email)) "
            + "     or (:suppressed = false and not exists (select 1 from SuppressionEntity s where s.workspaceId = :ws and s.email = c.email))) ")
    long countSearch(@org.springframework.data.repository.query.Param("ws") Long workspaceId,
                     @org.springframework.data.repository.query.Param("q") String q,
                     @org.springframework.data.repository.query.Param("listId") Long listId,
                     @org.springframework.data.repository.query.Param("suppressed") Boolean suppressed);

    @Query("select c from ContactEntity c, ListMembershipEntity m where m.listId = ?1 and m.contactId = c.id order by c.id")
    List<ContactEntity> findByListId(Long listId);

    /** Members past the keyset cursor, minus contacts who opted out of this list. */
    @Query("select c from ContactEntity c, ListMembershipEntity m where m.listId = ?1 and m.contactId = c.id "
            + "and c.id > ?2 "
            + "and not exists (select 1 from ListUnsubscribeEntity u where u.listId = ?1 and u.contactId = c.id) "
            + "order by c.id")
    List<ContactEntity> findSubscribedByListIdAfter(Long listId, Long afterId, Pageable pageable);

    @Query("select count(c) from ContactEntity c, ListMembershipEntity m where m.listId = ?1 and m.contactId = c.id")
    long countByListId(Long listId);
}
