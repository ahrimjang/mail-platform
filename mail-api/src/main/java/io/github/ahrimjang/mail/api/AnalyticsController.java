package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.AudienceHealthView;
import io.github.ahrimjang.mail.common.LinkClicksView;
import io.github.ahrimjang.mail.common.OpenHeatmapCell;
import io.github.ahrimjang.mail.core.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only aggregations backing the analytics page (bearer-protected like the
 * rest of the console API): the link-click ranking and audience health.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/links")
    public List<LinkClicksView> links(@RequestParam(defaultValue = "30") int days,
                                      @RequestParam(defaultValue = "10") int limit) {
        return analytics.topLinks(days, limit);
    }

    @GetMapping("/open-heatmap")
    public List<OpenHeatmapCell> openHeatmap(@RequestParam(defaultValue = "30") int days) {
        return analytics.openHeatmap(days);
    }

    @GetMapping("/audience-health")
    public AudienceHealthView audienceHealth(@RequestParam(defaultValue = "30") int days) {
        return analytics.audienceHealth(days);
    }
}
