package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactListRequest;
import io.github.ahrimjang.mail.common.ContactListView;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Use cases for managing contact lists and their memberships.
 * Lists are the targeting unit for campaigns: a campaign with a listId fans
 * out to every member at create time.
 */
@Service
public class ContactListService {

    private final ContactListRepository lists;
    private final ContactRepository contacts;

    /** Who is acting, for which tenant — resolved by the API adapter per request. */
    private final WorkspaceContext ctx;

    public ContactListService(ContactListRepository lists, ContactRepository contacts,
                           WorkspaceContext ctx) {
        this.ctx = ctx;
        this.lists = lists;
        this.contacts = contacts;
    }

    public ContactListView create(ContactListRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        ContactList list = ContactList.of(request.name(), request.description());
        list.setWorkspaceId(ctx.currentWorkspaceId());
        return toView(lists.save(list));
    }

    public List<ContactListView> list() {
        return lists.findByWorkspace(ctx.currentWorkspaceId()).stream()
                .map(this::toView)
                .toList();
    }

    /** Update a list's name/description. */
    public ContactListView update(Long id, ContactListRequest request) {
        ContactList list = lists.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("list not found: " + id));
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        list.setName(request.name());
        list.setDescription(request.description());
        return toView(lists.save(list));
    }

    /** Delete a list; its memberships go with it, the contacts themselves remain. */
    public void delete(Long id) {
        requireOwnedList(id);
        lists.deleteById(id);
    }

    /** Ids of every list the contact belongs to. */
    public List<Long> listsOf(Long contactId) {
        requireContact(contactId);
        return lists.findListIdsByContactId(contactId);
    }

    /** Replace the contact's memberships with exactly the given set of lists. */
    public List<Long> replaceListsOf(Long contactId, List<Long> listIds) {
        requireContact(contactId);
        if (listIds == null) {
            throw new IllegalArgumentException("listIds is required");
        }
        List<Long> distinct = listIds.stream().distinct().toList();
        for (Long listId : distinct) {
            requireOwnedList(listId);
        }
        lists.replaceMembershipsForContact(contactId, distinct);
        return lists.findListIdsByContactId(contactId);
    }

    public List<ContactView> members(Long listId) {
        requireOwnedList(listId);
        return contacts.findByListId(listId).stream()
                .map(c -> new ContactView(
                        c.getId(),
                        c.getEmail(),
                        c.getFirstName(),
                        c.getLastName(),
                        c.getAttributes(),
                        c.getCreatedAt(),
                        c.getConsentSource(),
                        c.getConsentedAt()))
                .toList();
    }

    public void addMember(Long listId, Long contactId) {
        requireOwnedList(listId);
        contacts.findById(contactId)
                .filter(c -> ctx.currentWorkspaceId().equals(c.getWorkspaceId()))
                .orElseThrow(() -> new NoSuchElementException("contact not found: " + contactId));
        lists.addMember(listId, contactId);
    }

    public void removeMember(Long listId, Long contactId) {
        requireOwnedList(listId);
        lists.removeMember(listId, contactId);
    }

    private void requireContact(Long contactId) {
        contacts.findById(contactId)
                .filter(c -> ctx.currentWorkspaceId().equals(c.getWorkspaceId()))
                .orElseThrow(() -> new NoSuchElementException("contact not found: " + contactId));
    }

    private ContactList requireOwnedList(Long listId) {
        return lists.findById(listId)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("list not found: " + listId));
    }

    private boolean owned(ContactList list) {
        return ctx.currentWorkspaceId().equals(list.getWorkspaceId());
    }

    private ContactListView toView(ContactList list) {
        return new ContactListView(
                list.getId(),
                list.getName(),
                list.getDescription(),
                lists.countMembers(list.getId()),
                list.getCreatedAt()
        );
    }
}
