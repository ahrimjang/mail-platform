package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.ContactRequest;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.common.ImportResult;
import io.github.ahrimjang.mail.core.service.ContactService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * CRUD API for individual contacts plus bulk CSV import, optionally adding the
 * imported contacts to an existing list.
 */
@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactService contacts;

    public ContactController(ContactService contacts) {
        this.contacts = contacts;
    }

    @GetMapping
    public List<ContactView> list() {
        return contacts.list();
    }

    @PostMapping
    public ResponseEntity<ContactView> create(@RequestBody ContactRequest request) {
        ContactView view = contacts.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        contacts.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Import "email,firstName,lastName" lines; existing addresses are skipped, all end up in {@code listId} if given. */
    @PostMapping(value = "/import", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ImportResult importCsv(@RequestBody String csv,
                                  @RequestParam(value = "listId", required = false) Long listId) {
        return contacts.importCsv(csv, listId);
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
