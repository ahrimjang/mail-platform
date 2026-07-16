package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Adapter: implements the core {@link ListUnsubscribeRepository} port over Spring Data JPA.
 */
@Repository
public class JpaListUnsubscribeRepository implements ListUnsubscribeRepository {

    private final ListUnsubscribeJpaRepository jpa;


    public JpaListUnsubscribeRepository(ListUnsubscribeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Long listId, Long contactId, String reason) {
        // Idempotent: a second unsubscribe click keeps the original record (and its
        // timestamp) instead of tripping the unique constraint.
        if (jpa.existsByListIdAndContactId(listId, contactId)) {
            return;
        }
        jpa.save(new ListUnsubscribeEntity(null, listId, contactId, reason, Instant.now()));
    }

    @Override
    public boolean exists(Long listId, Long contactId) {
        return jpa.existsByListIdAndContactId(listId, contactId);
    }

    @Override
    public List<Long> findListIdsByContactId(Long contactId) {
        return jpa.findListIdsByContactId(contactId);
    }

    @Override
    public void delete(Long listId, Long contactId) {
        jpa.deleteByListIdAndContactId(listId, contactId);
    }

    @Override
    public List<OptOut> findByContact(Long contactId) {
        return jpa.findByContactIdOrderByCreatedAtDesc(contactId).stream()
                .map(e -> new OptOut(e.getListId(), e.getReason(), e.getCreatedAt()))
                .toList();
    }

    @Override
    public List<ListCount> countByList(Long workspaceId) {
        return jpa.countByList(workspaceId).stream()
                .map(row -> new ListCount((Long) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    @Override
    public java.util.List<ContactOptOut> findByContactIds(java.util.List<Long> contactIds) {
        if (contactIds.isEmpty()) {
            return java.util.List.of();
        }
        return jpa.findByContactIdIn(contactIds).stream()
                .map(u -> new ContactOptOut(u.getContactId(), u.getListId()))
                .toList();
    }
}
