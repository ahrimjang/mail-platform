package io.github.ahrimjang.mail.common;

import java.util.List;

/**
 * Dashboard summary: audience size plus a gap-free daily activity series
 * (oldest day first, one entry per day even when nothing happened).
 */
public record DashboardView(
        long contacts,
        long suppressed,
        List<DashboardDay> daily
) {
}
