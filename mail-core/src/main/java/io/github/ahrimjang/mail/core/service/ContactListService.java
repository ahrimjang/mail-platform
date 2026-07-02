package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.ContactListRequest;
import io.github.ahrimjang.mail.common.ContactListView;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.core.domain.ContactList;
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

    public ContactListService(ContactListRepository lists, ContactRepository contacts) {
        this.lists = lists;
        this.contacts = contacts;
    }

    public ContactListView create(ContactListRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        ContactList saved = lists.save(ContactList.of(request.name(), request.description()));
        return toView(saved);
    }

    public List<ContactListView> list() {
        return lists.findAll().stream()
                .map(this::toView)
                .toList();
    }

    public void delete(Long id) {
        lists.deleteById(id);
    }

    public List<ContactView> members(Long listId) {
        lists.findById(listId)
                .orElseThrow(() -> new NoSuchElementException("list not found: " + listId));
        return contacts.findByListId(listId).stream()
                .map(c -> new ContactView(
                        c.getId(),
                        c.getEmail(),
                        c.getFirstName(),
                        c.getLastName(),
                        c.getAttributes(),
                        c.getCreatedAt()))
                .toList();
    }

    public void addMember(Long listId, Long contactId) {
        lists.findById(listId)
                .orElseThrow(() -> new NoSuchElementException("list not found: " + listId));
        contacts.findById(contactId)
                .orElseThrow(() -> new NoSuchElementException("contact not found: " + contactId));
        lists.addMember(listId, contactId);
    }

    public void removeMember(Long listId, Long contactId) {
        lists.removeMember(listId, contactId);
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
