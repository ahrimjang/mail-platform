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
    public List<ContactList> findAll() {
        return jpa.findAll().stream().map(this::toDomain).toList();
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

    private ContactListEntity toEntity(ContactList l) {
        return new ContactListEntity(l.getId(), l.getName(), l.getDescription(), l.getCreatedAt());
    }

    private ContactList toDomain(ContactListEntity e) {
        ContactList l = new ContactList();
        l.setId(e.getId());
        l.setName(e.getName());
        l.setDescription(e.getDescription());
        l.setCreatedAt(e.getCreatedAt());
        return l;
    }
}
