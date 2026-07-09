package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * Read model returned to API clients: campaign metadata plus live send progress.
 *
 * <p>Delivery counts ({@code total/pending/sent/failed/bounced/suppressed}) come
 * from message rows; engagement counts ({@code opened/clicked}) are derived from
 * recorded email events (distinct messages with at least one open/click).
 *
 * <p>{@code templateId}/{@code listId} record where content and audience came
 * from (null = authored directly / raw addresses); the matching names are
 * resolved at read time and stay null if the source was deleted since.
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
        Instant scheduledAt,
        Long templateId,
        String templateName,
        Long listId,
        String listName
) {
}
