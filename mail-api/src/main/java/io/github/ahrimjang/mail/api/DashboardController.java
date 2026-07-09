package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.DashboardView;
import io.github.ahrimjang.mail.core.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console dashboard summary: audience totals + daily send/engagement series.
 * JWT-protected like the rest of the console API.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public DashboardView stats(@RequestParam(defaultValue = "14") int days) {
        return dashboard.stats(days);
    }
}
