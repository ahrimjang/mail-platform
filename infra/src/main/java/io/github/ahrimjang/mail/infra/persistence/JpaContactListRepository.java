package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Adapter: implements the core {@link ContactListRepository} port over Spring Data JPA,
 * including the list-membership join rows.
 */
@Repository
public class JpaContactListRepository implements ContactListRepository {

    private final ContactListJpaRepository jpa;
    private final ListMembershipJpaRepository memberships;

    public JpaContactListRepository(ContactListJpaRepository jpa, ListMembershipJpaRepository memberships) {
        this.jpa = jpa;
        this.memberships = memberships;
    }

    @Override
    public ContactList save(ContactList list) {
        ContactListEntity saved = jpa.save(toEntity(list));
        return toDomain(saved);
    }

    @Override
    public Optional<ContactList> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public List<ContactList> findByWorkspace(Long workspaceId) {
        return jpa.findByWorkspaceIdOrderById(workspaceId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        memberships.deleteByListId(id);
        jpa.deleteById(id);
    }

    @Override
    public void addMember(Long listId, Long contactId) {
        if (!memberships.existsByListIdAndContactId(listId, contactId)) {
            memberships.save(new ListMembershipEntity(null, listId, contactId));
        }
    }

    @Override
    @Transactional
    public void removeMember(Long listId, Long contactId) {
        memberships.deleteByListIdAndContactId(listId, contactId);
    }

    @Override
    public long countMembers(Long listId) {
        return memberships.countByListId(listId);
    }

    @Override
    public List<Long> findListIdsByContactId(Long contactId) {
        return memberships.findByContactIdOrderByListId(contactId).stream()
                .map(ListMembershipEntity::getListId)
                .toList();
    }

    @Override
    @Transactional
    public void replaceMembershipsForContact(Long contactId, List<Long> listIds) {
        // Diff-based replace: only delete what left and insert what's new.
        // A naive wipe-and-reinsert breaks here — Hibernate flushes INSERTs before
        // the deferred derived DELETEs, so re-inserting a kept membership collides
        // with its not-yet-deleted row on uk_list_memberships.
        java.util.Set<Long> target = new java.util.HashSet<>(listIds);
        java.util.Set<Long> current = new java.util.HashSet<>(findListIdsByContactId(contactId));
        for (Long listId : current) {
            if (!target.contains(listId)) {
                memberships.deleteByListIdAndContactId(listId, contactId);
            }
        }
        for (Long listId : target) {
            if (!current.contains(listId)) {
                memberships.save(new ListMembershipEntity(null, listId, contactId));
            }
        }
    }

    @Override
    public java.util.List<Membership> findMembershipsByContactIds(java.util.List<Long> contactIds) {
        if (contactIds.isEmpty()) {
            return java.util.List.of();
        }
        return memberships.findByContactIdIn(contactIds).stream()
                .map(m -> new Membership(m.getContactId(), m.getListId()))
                .toList();
    }

    private ContactListEntity toEntity(ContactList l) {
        ContactListEntity entity = new ContactListEntity(l.getId(), l.getName(), l.getDescription(), l.getCreatedAt());
        entity.setWorkspaceId(l.getWorkspaceId());
        return entity;
    }

    private ContactList toDomain(ContactListEntity e) {
        ContactList l = new ContactList();
        l.setId(e.getId());
        l.setWorkspaceId(e.getWorkspaceId());
        l.setName(e.getName());
        l.setDescription(e.getDescription());
        l.setCreatedAt(e.getCreatedAt());
        return l;
    }
}
