package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.CreateCampaignRequest;
import io.github.ahrimjang.mail.core.service.CampaignService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
