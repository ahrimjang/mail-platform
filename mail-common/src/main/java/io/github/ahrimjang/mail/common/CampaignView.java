package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * Read model returned to API clients: campaign metadata plus live send progress.
 *
 * <p>Delivery counts ({@code total/pending/sent/failed/bounced/suppressed}) come
 * from message rows; engagement counts ({@code opened/clicked}) are derived from
 * recorded email events (distinct messages with at least one open/click).
 */
public record CampaignView(
        Long id,
        String subject,
        CampaignStatus status,
        long total,
        long pending,
        long sent,
        long failed,
        long bounced,
        long suppressed,
        long opened,
        long clicked,
        Instant createdAt,
        String senderName,
        String senderEmail,
        Instant scheduledAt
) {
}
