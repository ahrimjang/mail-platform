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
