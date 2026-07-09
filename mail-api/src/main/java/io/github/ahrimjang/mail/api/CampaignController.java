package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.CreateCampaignRequest;
import io.github.ahrimjang.mail.common.MessageView;
import io.github.ahrimjang.mail.common.SendLogEntry;
import io.github.ahrimjang.mail.core.service.CampaignService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * Public API for creating bulk-mail campaigns and polling their send progress.
 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaigns;

    public CampaignController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    /** Create a campaign and enqueue one message per recipient (returns immediately). */
    @PostMapping
    public ResponseEntity<CampaignView> create(@RequestBody CreateCampaignRequest request) {
        CampaignView view = campaigns.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    public List<CampaignView> list() {
        return campaigns.list();
    }

    @GetMapping("/{id}")
    public CampaignView get(@PathVariable Long id) {
        return campaigns.get(id);
    }

    /**
     * Cancel a scheduled campaign before its release. 409 when the campaign was
     * already released (or was never deferred) — cancellation is only possible
     * while the queue publish is still pending.
     */
    @PostMapping("/{id}/cancel")
    public CampaignView cancel(@PathVariable Long id) {
        return campaigns.cancelSchedule(id);
    }

    /** The mail this campaign sends: subject + HTML body snapshot (heavy — not part of the polled view). */
    @GetMapping("/{id}/content")
    public io.github.ahrimjang.mail.common.CampaignContentView content(@PathVariable Long id) {
        return campaigns.content(id);
    }

    /** Per-recipient drill-down: the campaign's most recently updated deliveries, newest first. */
    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable Long id,
                                      @RequestParam(defaultValue = "50") int limit) {
        return campaigns.recentMessages(id, limit);
    }

    /** Aggregated send log: time-bucketed counts per outcome — stays short for huge campaigns. */
    @GetMapping("/{id}/log")
    public List<SendLogEntry> log(@PathVariable Long id,
                                  @RequestParam(defaultValue = "10") int bucketSeconds,
                                  @RequestParam(defaultValue = "50") int limit) {
        return campaigns.sendLog(id, bucketSeconds, limit);
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
