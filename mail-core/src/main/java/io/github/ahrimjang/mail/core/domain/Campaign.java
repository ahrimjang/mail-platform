package io.github.ahrimjang.mail.core.domain;

import io.github.ahrimjang.mail.common.CampaignStatus;

import java.time.Instant;

/**
 * Domain model of a bulk-mail campaign. Pure POJO — no JPA / framework concerns.
 *
 * <p>{@code senderName}/{@code senderEmail} override the SMTP default From when
 * present. {@code scheduledAt} marks a deferred campaign; {@code enqueuedAt}
 * records when its messages were released to the send queue (null = not yet —
 * the worker's scheduler claims and releases due campaigns exactly once).
 */
public class Campaign {

    private Long id;
    // Console display name (null = fall back to subject) and free-form note.
    private String name;
    private String description;
    private String subject;
    private String body;
    private CampaignStatus status;
    private Instant createdAt;
    private String senderName;
    private String senderEmail;
    private Instant scheduledAt;
    private Instant enqueuedAt;
    private Instant completedAt;
    // Optional period end: engagement observed after this instant is dropped.
    private Instant endsAt;
    // DRAFT only: ad-hoc recipients typed so far, newline-separated (no
    // mail_messages rows exist yet to carry them).
    private String draftRecipients;
    // Soft provenance references (content/audience snapshotted at create time):
    // kept for display even if the template or list is deleted later.
    private Long templateId;
    private Long listId;
    // Engagement segment (list campaigns only): fan-out keeps a member only if
    // their open/click rate over delivered mail clears these floors, evaluated
    // at fan-out time. Null = no condition.
    private Integer segMinOpenPercent;
    private Integer segMinClickPercent;
    // A/B split test: variant B content and the share of recipients getting it.
    // Either B field non-null makes the campaign an A/B test.
    private String abSubjectB;
    private String abBodyB;
    private Integer abSplitPercent;
    // A/B winner flow: only abTestPercent% of the audience gets the test; after
    // abEvalWaitMinutes the better variant (by abEvalMetric) becomes abWinner and
    // the held-back remainder is sent with it. All null = plain split-only A/B.
    private Integer abTestPercent;
    private String abEvalMetric;
    private Integer abEvalWaitMinutes;
    private Instant abEvaluateAt;
    private String abWinner;

    public Campaign() {
    }

    /** Factory for a freshly authored campaign, before persistence. */
    public static Campaign draft(String subject, String body) {
        Campaign c = new Campaign();
        c.subject = subject;
        c.body = body;
        c.status = CampaignStatus.DRAFT;
        c.createdAt = Instant.now();
        return c;
    }

    public Long getId() {
        return id;
    }


    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public void setStatus(CampaignStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public void setEnqueuedAt(Instant enqueuedAt) {
        this.enqueuedAt = enqueuedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public String getDraftRecipients() {
        return draftRecipients;
    }

    public void setDraftRecipients(String draftRecipients) {
        this.draftRecipients = draftRecipients;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getListId() {
        return listId;
    }

    public void setListId(Long listId) {
        this.listId = listId;
    }

    /** True when fan-out should filter the list by engagement rates. */
    public boolean hasEngagementSegment() {
        return segMinOpenPercent != null || segMinClickPercent != null;
    }

    public Integer getSegMinOpenPercent() {
        return segMinOpenPercent;
    }

    public void setSegMinOpenPercent(Integer segMinOpenPercent) {
        this.segMinOpenPercent = segMinOpenPercent;
    }

    public Integer getSegMinClickPercent() {
        return segMinClickPercent;
    }

    public void setSegMinClickPercent(Integer segMinClickPercent) {
        this.segMinClickPercent = segMinClickPercent;
    }

    /** True when the campaign carries any variant B content (subject and/or body). */
    public boolean isAbTest() {
        return abSubjectB != null || abBodyB != null;
    }

    public String getAbSubjectB() {
        return abSubjectB;
    }

    public void setAbSubjectB(String abSubjectB) {
        this.abSubjectB = abSubjectB;
    }

    public String getAbBodyB() {
        return abBodyB;
    }

    public void setAbBodyB(String abBodyB) {
        this.abBodyB = abBodyB;
    }

    public Integer getAbSplitPercent() {
        return abSplitPercent;
    }

    public void setAbSplitPercent(Integer abSplitPercent) {
        this.abSplitPercent = abSplitPercent;
    }

    /** True when this A/B campaign runs the winner flow (test share + held-out remainder). */
    public boolean hasWinnerFlow() {
        return isAbTest() && abTestPercent != null;
    }

    public Integer getAbTestPercent() {
        return abTestPercent;
    }

    public void setAbTestPercent(Integer abTestPercent) {
        this.abTestPercent = abTestPercent;
    }

    public String getAbEvalMetric() {
        return abEvalMetric;
    }

    public void setAbEvalMetric(String abEvalMetric) {
        this.abEvalMetric = abEvalMetric;
    }

    public Integer getAbEvalWaitMinutes() {
        return abEvalWaitMinutes;
    }

    public void setAbEvalWaitMinutes(Integer abEvalWaitMinutes) {
        this.abEvalWaitMinutes = abEvalWaitMinutes;
    }

    public Instant getAbEvaluateAt() {
        return abEvaluateAt;
    }

    public void setAbEvaluateAt(Instant abEvaluateAt) {
        this.abEvaluateAt = abEvaluateAt;
    }

    public String getAbWinner() {
        return abWinner;
    }

    public void setAbWinner(String abWinner) {
        this.abWinner = abWinner;
    }
}
