package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactRequest;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.common.ImportResult;
import io.github.ahrimjang.mail.common.UpdateContactRequest;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Use cases for managing contacts, including bulk CSV import.
 *
 * <p>Contacts carry no status — whether an address may be mailed is decided
 * solely by the suppression list at dispatch time. Import is idempotent per
 * email: existing addresses are skipped (but still added to the target list).
 */
@Service
public class ContactService {

    private final ContactRepository contacts;
    private final ContactListRepository lists;

    /** Who is acting, for which tenant — resolved by the API adapter per request. */
    private final WorkspaceContext ctx;

    public ContactService(ContactRepository contacts, ContactListRepository lists,
                           WorkspaceContext ctx) {
        this.ctx = ctx;
        this.contacts = contacts;
        this.lists = lists;
    }

    public ContactView create(ContactRequest request) {
        if (request.email() == null || !request.email().contains("@")) {
            throw new IllegalArgumentException("valid email is required");
        }
        if (contacts.existsByWorkspaceAndEmail(ctx.currentWorkspaceId(), request.email())) {
            throw new IllegalStateException("contact already exists: " + request.email());
        }
        Contact contact = Contact.of(request.email(), request.firstName(), request.lastName(), request.attributes());
        contact.setWorkspaceId(ctx.currentWorkspaceId());
        return toView(contacts.save(contact));
    }

    public List<ContactView> list() {
        return contacts.findByWorkspace(ctx.currentWorkspaceId()).stream()
                .map(this::toView)
                .toList();
    }

    /** Rename only — the email is the contact's identity and other systems key on it. */
    public ContactView update(Long id, UpdateContactRequest request) {
        Contact contact = contacts.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("contact not found: " + id));
        contact.setFirstName(blankToNull(request.firstName()));
        contact.setLastName(blankToNull(request.lastName()));
        return toView(contacts.save(contact));
    }

    public void delete(Long id) {
        Contact contact = contacts.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("contact not found: " + id));
        contacts.deleteById(contact.getId());
    }

    /**
     * Import contacts from CSV text ({@code email,firstName,lastName} per line).
     * Lines without a valid email are skipped; already-known emails are skipped
     * but still added to {@code listId} when given.
     */
    public ImportResult importCsv(String csv, Long listId) {
        Long workspaceId = ctx.currentWorkspaceId();
        if (listId != null) {
            lists.findById(listId)
                    .filter(l -> workspaceId.equals(l.getWorkspaceId()))
                    .orElseThrow(() -> new NoSuchElementException("list not found: " + listId));
        }
        int imported = 0;
        int skipped = 0;
        for (String line : csv.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", 3);
            String email = parts[0].trim();
            if (!email.contains("@")) {
                skipped++;
                continue;
            }
            Optional<Contact> existing = contacts.findByWorkspaceAndEmail(workspaceId, email);
            Contact contact = existing.orElseGet(() -> {
                Contact fresh = Contact.of(
                        email,
                        parts.length > 1 ? parts[1].trim() : null,
                        parts.length > 2 ? parts[2].trim() : null,
                        new HashMap<>());
                fresh.setWorkspaceId(workspaceId);
                return contacts.save(fresh);
            });
            if (existing.isPresent()) {
                skipped++;
            } else {
                imported++;
            }
            if (listId != null) {
                lists.addMember(listId, contact.getId());
            }
        }
        return new ImportResult(imported, skipped);
    }

    private boolean owned(Contact c) {
        return ctx.currentWorkspaceId().equals(c.getWorkspaceId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ContactView toView(Contact contact) {
        return new ContactView(
                contact.getId(),
                contact.getEmail(),
                contact.getFirstName(),
                contact.getLastName(),
                contact.getAttributes(),
                contact.getCreatedAt()
        );
    }
}
