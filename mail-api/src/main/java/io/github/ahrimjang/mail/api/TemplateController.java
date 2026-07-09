package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.RenderedTemplate;
import io.github.ahrimjang.mail.common.TemplateRequest;
import io.github.ahrimjang.mail.common.TemplateView;
import io.github.ahrimjang.mail.core.service.TemplateService;
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
 * CRUD API for reusable mail templates plus a preview endpoint that renders
 * {{variable}} placeholders with caller-supplied values.
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templates;

    public TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    public List<TemplateView> list() {
        return templates.list();
    }

    @PostMapping
    public ResponseEntity<TemplateView> create(@RequestBody TemplateRequest request) {
        TemplateView view = templates.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/{id}")
    public TemplateView get(@PathVariable Long id) {
        return templates.get(id);
    }

    @PutMapping("/{id}")
    public TemplateView update(@PathVariable Long id, @RequestBody TemplateRequest request) {
        return templates.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templates.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Render the template's subject/body with the given variables without sending anything. */
    @PostMapping("/{id}/preview")
    public RenderedTemplate preview(@PathVariable Long id, @RequestBody Map<String, String> variables) {
        return templates.preview(id, variables);
    }

    /** Restore an edited built-in template to its original content. 409 for user templates. */
    @PostMapping("/{id}/reset")
    public TemplateView reset(@PathVariable Long id) {
        return templates.resetBuiltin(id);
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
