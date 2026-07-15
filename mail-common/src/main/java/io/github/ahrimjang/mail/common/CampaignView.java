package io.github.ahrimjang.mail.common;

import java.time.Instant;
import java.util.List;

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
 *
 * <p>{@code name} is the console display name (null = fall back to the
 * subject); {@code description} is a free-form note (null = none).
 *
 * <p>{@code variants} carries per-variant stats of an A/B campaign (null for
 * plain campaigns). {@code abTestPercent}/{@code abEvalMetric}/{@code abWinner}/
 * {@code abEvaluateAt} describe a winner-flow A/B campaign (all null otherwise):
 * only the test share receives variants; after the evaluation time the winning
 * variant goes out to the held-back remainder.
 */
public record CampaignView(
        Long id,
        String name,
        String description,
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
        Instant enqueuedAt,
        Instant completedAt,
        Long templateId,
        String templateName,
        Long listId,
        String listName,
        Integer abTestPercent,
        String abEvalMetric,
        String abWinner,
        Instant abEvaluateAt,
        List<VariantStats> variants
) {

    /** Per-variant delivery/engagement stats of an A/B campaign. */
    public record VariantStats(String variant, long total, long sent, long opened, long clicked) {}
}
