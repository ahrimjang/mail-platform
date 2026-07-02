package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.TransactionalRequest;
import io.github.ahrimjang.mail.core.service.CampaignService;
import io.github.ahrimjang.mail.core.service.TransactionalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * API for single-recipient transactional sends: renders a template with the
 * request variables and enqueues it through the regular campaign pipeline.
 */
@RestController
@RequestMapping("/api/transactional")
public class TransactionalController {

    private final TransactionalService transactional;
    private final CampaignService campaigns;

    public TransactionalController(TransactionalService transactional, CampaignService campaigns) {
        this.transactional = transactional;
        this.campaigns = campaigns;
    }

    /** Render and enqueue the mail, then return the backing single-recipient campaign. */
    @PostMapping
    public ResponseEntity<CampaignView> send(@RequestBody TransactionalRequest request) {
        Long id = transactional.send(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.get(id));
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
