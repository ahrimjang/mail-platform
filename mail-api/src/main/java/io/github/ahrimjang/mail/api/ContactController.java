package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.ContactActivityView;
import io.github.ahrimjang.mail.common.ContactMessageView;
import io.github.ahrimjang.mail.common.ContactRequest;
import io.github.ahrimjang.mail.common.ContactView;
import io.github.ahrimjang.mail.common.ImportResult;
import io.github.ahrimjang.mail.common.SubscriptionView;
import io.github.ahrimjang.mail.common.UpdateContactListsRequest;
import io.github.ahrimjang.mail.common.UpdateContactRequest;
import io.github.ahrimjang.mail.common.UpdateSubscriptionRequest;
import io.github.ahrimjang.mail.core.service.ContactActivityService;
import io.github.ahrimjang.mail.core.service.ContactListService;
import io.github.ahrimjang.mail.core.service.ContactService;
import io.github.ahrimjang.mail.core.service.SuppressionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final ContactListService lists;
    private final SuppressionService suppressions;
    private final ContactActivityService activity;

    public ContactController(ContactService contacts,
                             ContactListService lists,
                             SuppressionService suppressions,
                             ContactActivityService activity) {
        this.contacts = contacts;
        this.lists = lists;
        this.suppressions = suppressions;
        this.activity = activity;
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

    /** Rename a contact (email stays — it is the identity suppressions key on). */
    @PutMapping("/{id}")
    public ContactView update(@PathVariable Long id, @RequestBody UpdateContactRequest request) {
        return contacts.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        contacts.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Merged activity timeline, newest first. */
    @GetMapping("/{id}/activity")
    public List<ContactActivityView> activity(@PathVariable Long id,
                                              @RequestParam(defaultValue = "30") int limit) {
        return activity.activity(id, limit);
    }

    /** Deliveries to this contact, newest first. */
    @GetMapping("/{id}/messages")
    public List<ContactMessageView> messages(@PathVariable Long id,
                                             @RequestParam(defaultValue = "20") int limit) {
        return activity.messages(id, limit);
    }

    /** Ids of every list this contact belongs to. */
    @GetMapping("/{id}/lists")
    public List<Long> listsOf(@PathVariable Long id) {
        return lists.listsOf(id);
    }

    /** Replace this contact's list memberships with exactly the given set. */
    @PutMapping("/{id}/lists")
    public List<Long> replaceLists(@PathVariable Long id, @RequestBody UpdateContactListsRequest request) {
        return lists.replaceListsOf(id, request.listIds());
    }

    /** Subscription state of this contact, derived from the suppression list. */
    @GetMapping("/{id}/subscription")
    public SubscriptionView subscription(@PathVariable Long id) {
        return suppressions.subscriptionOf(id);
    }

    /** Suppress (true) or unsuppress (false) this contact's address. */
    @PutMapping("/{id}/subscription")
    public SubscriptionView updateSubscription(@PathVariable Long id, @RequestBody UpdateSubscriptionRequest request) {
        return suppressions.updateSubscription(id, request.suppressed());
    }

    /** Lists this contact opted out of via the unsubscribe page (memberships stay). */
    @GetMapping("/{id}/list-unsubscribes")
    public List<Long> listUnsubscribes(@PathVariable Long id) {
        return suppressions.listUnsubscribesOf(id);
    }

    /** Operator-side re-subscribe: drop the contact's opt-out of the given list. */
    @DeleteMapping("/{id}/list-unsubscribes/{listId}")
    public List<Long> resubscribeToList(@PathVariable Long id, @PathVariable Long listId) {
        suppressions.resubscribeToList(id, listId);
        return suppressions.listUnsubscribesOf(id);
    }

    /** Operator-side list opt-out ("해지" status) — recorded as "manual", memberships stay. */
    @PostMapping("/{id}/list-unsubscribes/{listId}")
    public List<Long> optOutOfList(@PathVariable Long id, @PathVariable Long listId) {
        suppressions.optOutOfList(id, listId);
        return suppressions.listUnsubscribesOf(id);
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
