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
 * @param emailId     선택한 이메일(캠페인용 콘텐츠) — 제목·본문을 등록 시점에 스냅샷.
 *                    templateId 보다 우선한다 (이메일이 새 개념, 템플릿 직접 참조는 레거시 경로)
 * @param abEmailId   B안으로 선택한 이메일 — abTemplateId 의 이메일 버전, 역시 우선
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
        Instant endsAt,
        Long emailId,
        Long abEmailId,
        String replyTo   // 회신 주소 — 발신은 서비스 도메인, 답장은 이 주소로 (V33)
) {
    /** emailId(V27)·replyTo(V33) 도입 이전 시그니처 호환 — 미설정으로 위임. */
    public CreateCampaignRequest(String subject, String body, List<String> recipients, Long templateId,
                                 Long listId, String senderName, String senderEmail, Instant scheduledAt,
                                 String abSubjectB, String abBodyB, Long abTemplateId, Integer abSplitPercent,
                                 Integer abTestPercent, String abEvalMetric, Integer abEvalWaitMinutes,
                                 String name, String description, Integer segMinOpenPercent,
                                 Integer segMinClickPercent, Instant endsAt) {
        this(subject, body, recipients, templateId, listId, senderName, senderEmail, scheduledAt,
                abSubjectB, abBodyB, abTemplateId, abSplitPercent, abTestPercent, abEvalMetric,
                abEvalWaitMinutes, name, description, segMinOpenPercent, segMinClickPercent, endsAt,
                null, null, null);
    }
}
