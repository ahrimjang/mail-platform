package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactPageView;
import io.github.ahrimjang.mail.common.ContactRequest;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.common.ImportResult;
import io.github.ahrimjang.mail.common.UpdateContactRequest;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final SuppressionRepository suppressions;
    private final ListUnsubscribeRepository listUnsubscribes;
    private final ContactEngagementService engagement;

    /** Who is acting, for which tenant — resolved by the API adapter per request. */
    private final WorkspaceContext ctx;

    public ContactService(ContactRepository contacts, ContactListRepository lists,
                          SuppressionRepository suppressions,
                          ListUnsubscribeRepository listUnsubscribes,
                          ContactEngagementService engagement,
                          WorkspaceContext ctx) {
        this.ctx = ctx;
        this.contacts = contacts;
        this.lists = lists;
        this.suppressions = suppressions;
        this.listUnsubscribes = listUnsubscribes;
        this.engagement = engagement;
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
        // Consent provenance: an operator typed this address in by hand.
        contact.setConsentSource("MANUAL");
        contact.setConsentedAt(java.time.Instant.now());
        return toView(contacts.save(contact));
    }

    public List<ContactView> list() {
        return contacts.findByWorkspace(ctx.currentWorkspaceId()).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * One page of the recipients table, filtered in the database and enriched
     * with three batch queries (memberships, opt-outs, suppression) — never a
     * per-row round trip. {@code subscribed}: null = all, true = active only,
     * false = suppressed only. When an engagement floor is set the ranked
     * match from {@link ContactEngagementService} drives the page instead
     * (rates are computed, not materialized, so this path is in-memory).
     */
    public ContactPageView page(String q, Long listId, Boolean subscribed,
                                Integer minOpenPercent, Integer minClickPercent,
                                int offset, int limit) {
        Long workspaceId = ctx.currentWorkspaceId();
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        // The search treats "suppressed only" as subscribed=false and vice versa.
        Boolean suppressedFilter = subscribed == null ? null : !subscribed;

        List<Contact> pageRows;
        long total;
        boolean engaged = (minOpenPercent != null && minOpenPercent > 0)
                || (minClickPercent != null && minClickPercent > 0);
        if (!engaged) {
            pageRows = contacts.search(workspaceId, q, listId, suppressedFilter, safeOffset, safeLimit);
            total = contacts.countSearch(workspaceId, q, listId, suppressedFilter);
        } else {
            // Engagement mode: rates come from aggregates, so the ranked match is
            // computed first and the remaining filters narrow it in memory.
            String needle = q == null ? "" : q.trim().toLowerCase();
            List<Long> matchedIds = engagement.engagement(
                            1,
                            minOpenPercent == null ? 0 : minOpenPercent,
                            minClickPercent == null ? 0 : minClickPercent,
                            listId).stream()
                    .filter(v -> needle.isEmpty()
                            || v.email().toLowerCase().contains(needle)
                            || ((v.lastName() == null ? "" : v.lastName())
                                    + (v.firstName() == null ? "" : v.firstName())).toLowerCase().contains(needle))
                    .map(io.github.ahrimjang.mail.common.ContactEngagementView::contactId)
                    .toList();
            List<Contact> matched = matchedIds.stream()
                    .map(contacts::findById)
                    .flatMap(Optional::stream)
                    .toList();
            if (suppressedFilter != null) {
                java.util.Set<String> suppressedEmails = new java.util.HashSet<>(suppressions.findSuppressedEmails(
                        workspaceId, matched.stream().map(Contact::getEmail).toList()));
                boolean keepSuppressed = suppressedFilter;
                matched = matched.stream()
                        .filter(c -> suppressedEmails.contains(c.getEmail()) == keepSuppressed)
                        .toList();
            }
            total = matched.size();
            pageRows = matched.stream().skip(safeOffset).limit(safeLimit).toList();
        }

        return new ContactPageView(enrich(workspaceId, pageRows), total);
    }

    /** Three batch lookups turn a page of contacts into ready-to-render rows. */
    private List<ContactPageView.ContactRowView> enrich(Long workspaceId, List<Contact> pageRows) {
        List<Long> ids = pageRows.stream().map(Contact::getId).toList();
        Map<Long, List<Long>> memberships = new HashMap<>();
        lists.findMembershipsByContactIds(ids)
                .forEach(m -> memberships.computeIfAbsent(m.contactId(), k -> new java.util.ArrayList<>()).add(m.listId()));
        Map<Long, List<Long>> optOuts = new HashMap<>();
        listUnsubscribes.findByContactIds(ids)
                .forEach(u -> optOuts.computeIfAbsent(u.contactId(), k -> new java.util.ArrayList<>()).add(u.listId()));
        java.util.Set<String> suppressed = new java.util.HashSet<>(suppressions.findSuppressedEmails(
                workspaceId, pageRows.stream().map(Contact::getEmail).toList()));

        return pageRows.stream()
                .map(c -> new ContactPageView.ContactRowView(
                        c.getId(), c.getEmail(), c.getFirstName(), c.getLastName(),
                        c.getCreatedAt(), c.getConsentSource(), c.getConsentedAt(),
                        suppressed.contains(c.getEmail()),
                        memberships.getOrDefault(c.getId(), List.of()),
                        optOuts.getOrDefault(c.getId(), List.of())))
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
                // Consent provenance: this address arrived through a bulk import.
                fresh.setConsentSource("CSV_IMPORT");
                fresh.setConsentedAt(java.time.Instant.now());
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
                contact.getCreatedAt(),
                contact.getConsentSource(),
                contact.getConsentedAt()
        );
    }
}
