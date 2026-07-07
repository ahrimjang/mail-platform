package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.ContactListRequest;
import io.github.ahrimjang.mail.common.ContactListView;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.core.service.ContactListService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * CRUD API for contact lists and their memberships; lists are the targeting
 * unit for list-based campaigns.
 */
@RestController
@RequestMapping("/api/lists")
public class ContactListController {

    private final ContactListService lists;

    public ContactListController(ContactListService lists) {
        this.lists = lists;
    }

    @GetMapping
    public List<ContactListView> list() {
        return lists.list();
    }

    @PostMapping
    public ResponseEntity<ContactListView> create(@RequestBody ContactListRequest request) {
        ContactListView view = lists.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** Rename a list / update its description. */
    @PutMapping("/{id}")
    public ContactListView update(@PathVariable Long id, @RequestBody ContactListRequest request) {
        return lists.update(id, request);
    }

    /** Delete a list (memberships go with it; contacts are kept). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        lists.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    public List<ContactView> members(@PathVariable Long id) {
        return lists.members(id);
    }

    @PostMapping("/{id}/members/{contactId}")
    public ResponseEntity<Void> addMember(@PathVariable Long id, @PathVariable Long contactId) {
        lists.addMember(id, contactId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{contactId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id, @PathVariable Long contactId) {
        lists.removeMember(id, contactId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
