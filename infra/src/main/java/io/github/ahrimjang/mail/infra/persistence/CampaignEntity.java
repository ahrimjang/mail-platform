package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.CampaignStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "campaigns")
public class CampaignEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Console display name; null = fall back to the subject. */
    private String name;

    /** Free-form description of the campaign's purpose; null = none. */
    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    /** Optional From display name (null = SMTP default). */
    private String senderName;

    /** Optional From address (null = SMTP default). */
    private String senderEmail;

    /** Requested send time; null = immediate. */
    private Instant scheduledAt;

    /** When messages were released to the queue; null = awaiting the scheduler. */
    private Instant enqueuedAt;

    /** When the campaign finished draining; null while in flight (or legacy rows). */
    private Instant completedAt;

    /** Period end: engagement after this instant is not recorded; null = open-ended. */
    private Instant endsAt;

    /** DRAFT only: ad-hoc recipients typed so far, newline-separated. */
    @Column(columnDefinition = "text")
    private String draftRecipients;

    /** Template the content was snapshotted from; null = authored directly. */
    private Long templateId;

    /** Contact list the recipients were fanned out from; null = raw addresses. */
    private Long listId;

    /** Engagement segment floor: minimum open rate percent; null = no condition. */
    @Column(name = "seg_min_open_percent")
    private Integer segMinOpenPercent;

    /** Engagement segment floor: minimum click rate percent; null = no condition. */
    @Column(name = "seg_min_click_percent")
    private Integer segMinClickPercent;

    // Explicit column names: the default naming strategy maps the trailing
    // capital (abBodyB -> ab_bodyb) and would miss the migration's ab_body_b.
    /** A/B variant B subject; null = no subject test. */
    @Column(name = "ab_subject_b")
    private String abSubjectB;

    /** A/B variant B body; null = body shared between variants. */
    @Column(name = "ab_body_b", columnDefinition = "text")
    private String abBodyB;

    /** Share of recipients receiving variant B (1..99); null = not an A/B test. */
    @Column(name = "ab_split_percent")
    private Integer abSplitPercent;

    /** Share of the audience entering the A/B test (5..90); null = split-only A/B. */
    @Column(name = "ab_test_percent")
    private Integer abTestPercent;

    /** Winner metric, OPEN or CLICK; null = split-only A/B. */
    @Column(name = "ab_eval_metric", length = 8)
    private String abEvalMetric;

    /** Evaluation wait after the test batch is released, minutes. */
    @Column(name = "ab_eval_wait_minutes")
    private Integer abEvalWaitMinutes;

    /** When the winner should be decided; stamped when the test batch is released. */
    @Column(name = "ab_evaluate_at")
    private Instant abEvaluateAt;

    /** Decided winning variant ("A"/"B"); null until evaluated. */
    @Column(name = "ab_winner", length = 1)
    private String abWinner;

    protected CampaignEntity() {
    }

    public CampaignEntity(Long id, String name, String description, String subject, String body,
                          CampaignStatus status, Instant createdAt,
                          String senderName, String senderEmail, Instant scheduledAt, Instant enqueuedAt,
                          Instant completedAt, Instant endsAt, String draftRecipients, Long templateId, Long listId,
                          Integer segMinOpenPercent, Integer segMinClickPercent,
                          String abSubjectB, String abBodyB, Integer abSplitPercent,
                          Integer abTestPercent, String abEvalMetric, Integer abEvalWaitMinutes,
                          Instant abEvaluateAt, String abWinner) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.subject = subject;
        this.body = body;
        this.status = status;
        this.createdAt = createdAt;
        this.senderName = senderName;
        this.senderEmail = senderEmail;
        this.scheduledAt = scheduledAt;
        this.enqueuedAt = enqueuedAt;
        this.completedAt = completedAt;
        this.endsAt = endsAt;
        this.draftRecipients = draftRecipients;
        this.templateId = templateId;
        this.listId = listId;
        this.segMinOpenPercent = segMinOpenPercent;
        this.segMinClickPercent = segMinClickPercent;
        this.abSubjectB = abSubjectB;
        this.abBodyB = abBodyB;
        this.abSplitPercent = abSplitPercent;
        this.abTestPercent = abTestPercent;
        this.abEvalMetric = abEvalMetric;
        this.abEvalWaitMinutes = abEvalWaitMinutes;
        this.abEvaluateAt = abEvaluateAt;
        this.abWinner = abWinner;
    }

    public Long getId() {
        return id;
    }


    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
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

    public String getSenderName() {
        return senderName;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getDraftRecipients() {
        return draftRecipients;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public Long getListId() {
        return listId;
    }

    public Integer getSegMinOpenPercent() {
        return segMinOpenPercent;
    }

    public Integer getSegMinClickPercent() {
        return segMinClickPercent;
    }

    public String getAbSubjectB() {
        return abSubjectB;
    }

    public String getAbBodyB() {
        return abBodyB;
    }

    public Integer getAbSplitPercent() {
        return abSplitPercent;
    }

    public Integer getAbTestPercent() {
        return abTestPercent;
    }

    public String getAbEvalMetric() {
        return abEvalMetric;
    }

    public Integer getAbEvalWaitMinutes() {
        return abEvalWaitMinutes;
    }

    public Instant getAbEvaluateAt() {
        return abEvaluateAt;
    }

    public String getAbWinner() {
        return abWinner;
    }
}
