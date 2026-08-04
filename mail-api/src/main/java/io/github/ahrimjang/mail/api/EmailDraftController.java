package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.EmailDraftView;
import io.github.ahrimjang.mail.common.SaveEmailDraftRequest;
import io.github.ahrimjang.mail.core.service.EmailDraftService;
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

/** 이메일(캠페인용 콘텐츠) CRUD — Bearer 필수, 워크스페이스 스코프. */
@RestController
@RequestMapping("/api/emails")
public class EmailDraftController {

    private final EmailDraftService emails;

    public EmailDraftController(EmailDraftService emails) {
        this.emails = emails;
    }

    @GetMapping
    public List<EmailDraftView> list() {
        return emails.list();
    }

    @GetMapping("/{id}")
    public EmailDraftView get(@PathVariable Long id) {
        return emails.get(id);
    }

    @PostMapping
    public ResponseEntity<EmailDraftView> create(@RequestBody SaveEmailDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(emails.create(request));
    }

    @PutMapping("/{id}")
    public EmailDraftView update(@PathVariable Long id, @RequestBody SaveEmailDraftRequest request) {
        return emails.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        emails.delete(id);
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
}
