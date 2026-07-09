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

    void deleteById(Long id);
}
