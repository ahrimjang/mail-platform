package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Contact;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for contacts. Implemented by an infra adapter.
 */
public interface ContactRepository {

    Contact save(Contact contact);

    Optional<Contact> findById(Long id);

    Optional<Contact> findByEmail(String email);

    boolean existsByEmail(String email);

    List<Contact> findAll();

    /** Total number of contacts (dashboard audience size). */
    long count();

    /** All contacts that are members of the given list, ordered by id. */
    List<Contact> findByListId(Long listId);

    /**
     * Keyset page of a list's still-subscribed contacts (list opt-outs excluded)
     * with id > afterId, ordered by id, at most {@code limit} — streams large
     * lists without loading all. Fan-out reads this, so an opted-out contact is
     * excluded at send time even though their membership row remains.
     */
    List<Contact> findSubscribedByListIdAfter(Long listId, Long afterId, int limit);

    /** Number of contacts in a list (cheap size check without loading members). */
    long countByListId(Long listId);

    void deleteById(Long id);
}
