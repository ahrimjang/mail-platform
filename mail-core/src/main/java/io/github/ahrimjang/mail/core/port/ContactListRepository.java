package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.ContactList;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for contact lists and their memberships. Implemented by an infra adapter.
 */
public interface ContactListRepository {

    ContactList save(ContactList list);

    Optional<ContactList> findById(Long id);

    List<ContactList> findAll();

    void deleteById(Long id);

    /** Add a contact to a list; a no-op if the membership already exists. */
    void addMember(Long listId, Long contactId);

    /** Remove a contact from a list; a no-op if not a member. */
    void removeMember(Long listId, Long contactId);

    long countMembers(Long listId);
}
