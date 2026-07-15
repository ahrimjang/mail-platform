package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.AudienceHealthView;
import io.github.ahrimjang.mail.common.AudienceHealthView.ListOptOut;
import io.github.ahrimjang.mail.common.AudienceHealthView.ReasonCount;
import io.github.ahrimjang.mail.common.LinkClicksView;
import io.github.ahrimjang.mail.common.OpenHeatmapCell;
import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.ListUnsubscribeRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side aggregations for the analytics page beyond the dashboard series:
 * which links get clicked, and why addresses stop receiving mail (suppression
 * reasons + per-list opt-outs). The database does the grouping; this service
 * only clamps inputs and merges totals with the period's new entries.
 */
@Service
public class AnalyticsService {

    /** Same ceiling as the dashboard series — a quarter of daily data. */
    static final int MAX_DAYS = 90;
    static final int MAX_LINKS = 50;

    private final EmailEventRepository events;
    private final SuppressionRepository suppressions;
    private final ListUnsubscribeRepository listUnsubscribes;
    private final ContactListRepository lists;

    public AnalyticsService(EmailEventRepository events,
                            SuppressionRepository suppressions,
                            ListUnsubscribeRepository listUnsubscribes,
                            ContactListRepository lists) {
        this.events = events;
        this.suppressions = suppressions;
        this.listUnsubscribes = listUnsubscribes;
        this.lists = lists;
    }

    /** Most-clicked tracked links in the last {@code days}, best first. */
    public List<LinkClicksView> topLinks(int days, int limit) {
        Instant since = Instant.now().minus(Duration.ofDays(clamp(days, 1, MAX_DAYS)));
        return events.topClickedLinks(since, clamp(limit, 1, MAX_LINKS)).stream()
                .map(l -> new LinkClicksView(l.url(), l.clicks(), l.uniqueMessages()))
                .toList();
    }

    /** Suppression reasons (all-time + period) and per-list opt-out counts. */
    public AudienceHealthView audienceHealth(int days) {
        Instant since = Instant.now().minus(Duration.ofDays(clamp(days, 1, MAX_DAYS)));

        // Merge the all-time and in-period breakdowns onto one reason list,
        // keeping the all-time (largest-first) ordering.
        Map<String, long[]> byReason = new LinkedHashMap<>();
        suppressions.countByReason()
                .forEach(r -> byReason.put(r.reason(), new long[]{r.count(), 0}));
        suppressions.countByReasonSince(since)
                .forEach(r -> byReason.computeIfAbsent(r.reason(), k -> new long[]{0, 0})[1] = r.count());
        List<ReasonCount> reasons = byReason.entrySet().stream()
                .map(e -> new ReasonCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();

        List<ListOptOut> optOuts = listUnsubscribes.countByList().stream()
                .map(c -> new ListOptOut(
                        c.listId(),
                        lists.findById(c.listId()).map(ContactList::getName).orElse("(삭제된 리스트)"),
                        c.count()))
                .toList();

        return new AudienceHealthView(reasons, optOuts);
    }

    /** Opens by (weekday, hour) of the local calendar — when recipients actually read. */
    public List<OpenHeatmapCell> openHeatmap(int days) {
        Instant since = Instant.now().minus(Duration.ofDays(clamp(days, 1, MAX_DAYS)));
        return events.aggregateOpenHeatmap(since, ZoneId.systemDefault()).stream()
                .map(c -> new OpenHeatmapCell(c.dayOfWeek(), c.hour(), c.opens()))
                .toList();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
