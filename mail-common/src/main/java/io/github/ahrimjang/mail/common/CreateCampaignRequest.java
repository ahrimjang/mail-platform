package io.github.ahrimjang.mail.common;

import java.time.Instant;
import java.util.List;

/**
 * Request to create and enqueue a bulk-mail campaign.
 *
 * <p>Content comes either from direct {@code subject}/{@code body} or from a
 * {@code templateId}, whose subject/body are snapshotted at create time.
 * Recipients come either from the explicit {@code recipients} list or from a
 * {@code listId} targeting a contact list (one queued message per member).
 *
 * @param subject     mail subject line (ignored when {@code templateId} is set)
 * @param body        mail HTML body (ignored when {@code templateId} is set)
 * @param recipients  destination addresses; one queued message is created per entry
 * @param templateId  optional template whose content is snapshotted into the campaign
 * @param listId      optional contact list to fan the campaign out to
 * @param senderName  optional From display name (falls back to the SMTP default)
 * @param senderEmail optional From address (falls back to the SMTP default)
 * @param scheduledAt optional send time; a future instant defers enqueueing until
 *                    then (released by the worker's scheduler), null/past sends now
 * @param abSubjectB  optional variant B subject — non-null makes the campaign an A/B test
 *                    (ignored when {@code abTemplateId} is set)
 * @param abBodyB     optional variant B body — non-null makes the campaign an A/B test
 *                    (null with abSubjectB set = subject-only test, body stays shared;
 *                    ignored when {@code abTemplateId} is set)
 * @param abTemplateId optional template whose subject/body are snapshotted as variant B —
 *                    the A/B mirror of {@code templateId}
 * @param abSplitPercent share of recipients receiving variant B, 1..99 (null = 50)
 * @param abTestPercent share of the audience entering the A/B test, 5..90 —
 *                    the rest is held back and later receives the winning variant
 *                    (null = split-only A/B, no winner phase)
 * @param abEvalMetric winner metric, OPEN or CLICK (null = OPEN)
 * @param abEvalWaitMinutes evaluation wait after the test batch is released,
 *                    in minutes (null = 60)
 * @param name        optional display name shown in the console — null falls
 *                    back to the subject
 * @param description optional free-form description of the campaign's purpose
 * @param segMinOpenPercent  optional engagement floor (list campaigns only): fan-out
 *                    keeps a member only if their open rate is at least this percent
 * @param segMinClickPercent optional engagement floor (list campaigns only): same
 *                    for the click rate
 * @param endsAt      optional campaign period end — opens/clicks observed after
 *                    this instant are not recorded, freezing the campaign's rates
 */
public record CreateCampaignRequest(
        String subject,
        String body,
        List<String> recipients,
        Long templateId,
        Long listId,
        String senderName,
        String senderEmail,
        Instant scheduledAt,
        String abSubjectB,
        String abBodyB,
        Long abTemplateId,
        Integer abSplitPercent,
        Integer abTestPercent,
        String abEvalMetric,
        Integer abEvalWaitMinutes,
        String name,
        String description,
        Integer segMinOpenPercent,
        Integer segMinClickPercent,
        Instant endsAt
) {
}
