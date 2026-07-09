package io.github.ahrimjang.mail.common;

import java.time.LocalDate;

/**
 * One day of platform-wide send/engagement activity for the dashboard chart.
 * {@code failed} folds FAILED and BOUNCED outcomes together; {@code opened}/
 * {@code clicked} are distinct-message counts from the engagement event stream.
 */
public record DashboardDay(
        LocalDate date,
        long sent,
        long failed,
        long opened,
        long clicked
) {
}
