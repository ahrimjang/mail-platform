package io.github.ahrimjang.mail.infra.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter: implements the core {@link ContactRepository} port over Spring Data JPA.
 * The free-form attribute map is serialized to a JSON string column.
 */
@Repository
public class JpaContactRepository implements ContactRepository {

    private final ContactJpaRepository jpa;
    private final ListMembershipJpaRepository memberships;
    private final ObjectMapper mapper = new ObjectMapper();

    public JpaContactRepository(ContactJpaRepository jpa, ListMembershipJpaRepository memberships) {
        this.jpa = jpa;
        this.memberships = memberships;
    }

    @Override
    public Contact save(Contact contact) {
        ContactEntity saved = jpa.save(toEntity(contact));
        return toDomain(saved);
    }

    @Override
    public Optional<Contact> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Contact> findByEmail(String email) {
        return jpa.findByEmail(email).map(this::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }

    @Override
    public List<Contact> findAll() {
        return jpa.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public List<Contact> findByListId(Long listId) {
        return jpa.findByListId(listId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        memberships.deleteByContactId(id);
        jpa.deleteById(id);
    }

    private ContactEntity toEntity(Contact c) {
        return new ContactEntity(c.getId(), c.getEmail(), c.getFirstName(), c.getLastName(),
                writeAttributes(c.getAttributes()), c.getCreatedAt());
    }

    private Contact toDomain(ContactEntity e) {
        Contact c = new Contact();
        c.setId(e.getId());
        c.setEmail(e.getEmail());
        c.setFirstName(e.getFirstName());
        c.setLastName(e.getLastName());
        c.setAttributes(readAttributes(e.getAttributesJson()));
        c.setCreatedAt(e.getCreatedAt());
        return c;
    }

    private String writeAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(attributes);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize contact attributes", ex);
        }
    }

    private Map<String, String> readAttributes(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<HashMap<String, String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize contact attributes", ex);
        }
    }
}
