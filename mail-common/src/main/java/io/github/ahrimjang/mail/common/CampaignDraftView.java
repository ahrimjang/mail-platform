package io.github.ahrimjang.mail.common;

import java.time.Instant;
import java.util.List;

/**
 * Everything the compose form needs to resume editing a DRAFT campaign.
 * Mirrors the editable fields of {@link CreateCampaignRequest}; recipients are
 * the ad-hoc addresses typed so far (a draft has no message rows yet).
 */
public record CampaignDraftView(
        Long id,
        String name,
        String description,
        String subject,
        String body,
        Long templateId,
        List<String> recipients,
        Long listId,
        String senderName,
        String senderEmail,
        Instant scheduledAt,
        Instant endsAt,
        String abSubjectB,
        String abBodyB,
        Integer abTestPercent,
        String abEvalMetric,
        Integer abEvalWaitMinutes,
        Integer segMinOpenPercent,
        Integer segMinClickPercent
) {
}
