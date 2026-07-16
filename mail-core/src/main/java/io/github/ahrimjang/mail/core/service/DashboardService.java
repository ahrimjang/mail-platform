package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.DashboardDay;
import io.github.ahrimjang.mail.common.DashboardView;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side aggregation for the console dashboard: audience totals plus a
 * gap-free daily series of sends and engagement. The database does the heavy
 * grouping; this service only merges the two aggregations onto one calendar.
 */
@Service
public class DashboardService {

    /** Longest series a client may request — 90 daily points is already a quarter. */
    static final int MAX_DAYS = 90;

    // Index layout of the per-day accumulator: [sent, failed, opened, clicked].
    private static final int SENT = 0;
    private static final int FAILED = 1;
    private static final int OPENED = 2;
    private static final int CLICKED = 3;

    private final MailMessageRepository messages;
    private final EmailEventRepository events;
    private final ContactRepository contacts;
    private final SuppressionRepository suppressions;

    /** Who is acting, for which tenant — resolved by the API adapter per request. */
    private final WorkspaceContext ctx;

    public DashboardService(MailMessageRepository messages,
                            EmailEventRepository events,
                            ContactRepository contacts,
                            SuppressionRepository suppressions,
                           WorkspaceContext ctx) {
        this.ctx = ctx;
        this.messages = messages;
        this.events = events;
        this.contacts = contacts;
        this.suppressions = suppressions;
    }

    /**
     * Builds the dashboard summary for the last {@code days} calendar days
     * (today inclusive). Days without activity appear as zero rows so the
     * chart's x-axis is continuous.
     */
    public DashboardView stats(int days) {
        int span = Math.min(Math.max(days, 1), MAX_DAYS);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate first = today.minusDays(span - 1L);
        Instant since = first.atStartOfDay(zone).toInstant();

        // Zero-filled calendar first; the aggregations then land on top of it.
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (int i = 0; i < span; i++) {
            byDay.put(first.plusDays(i), new long[4]);
        }

        for (MailMessageRepository.DailyOutcome o : messages.aggregateDailyOutcomes(ctx.currentWorkspaceId(), since, zone)) {
            long[] acc = byDay.get(o.day());
            if (acc == null) {
                continue; // clock skew around the window edge — not worth failing over
            }
            switch (o.status()) {
                case SENT -> acc[SENT] += o.count();
                case FAILED, BOUNCED -> acc[FAILED] += o.count();
                default -> { /* non-terminal statuses are not part of the chart */ }
            }
        }

        for (EmailEventRepository.DailyEngagement e : events.aggregateDailyEngagement(ctx.currentWorkspaceId(), since, zone)) {
            long[] acc = byDay.get(e.day());
            if (acc == null) {
                continue;
            }
            switch (e.type()) {
                case OPEN -> acc[OPENED] += e.distinctMessages();
                case CLICK -> acc[CLICKED] += e.distinctMessages();
                default -> { /* bounces already show up as failed outcomes */ }
            }
        }

        List<DashboardDay> daily = new ArrayList<>(byDay.size());
        byDay.forEach((day, acc) ->
                daily.add(new DashboardDay(day, acc[SENT], acc[FAILED], acc[OPENED], acc[CLICKED])));

        return new DashboardView(contacts.countByWorkspace(ctx.currentWorkspaceId()),
                suppressions.countByWorkspace(ctx.currentWorkspaceId()), daily);
    }
}
