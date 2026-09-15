package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignContentView;
import io.github.ahrimjang.mail.common.CampaignDraftView;
import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.CreateCampaignRequest;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.LinkClicksView;
import io.github.ahrimjang.mail.common.MessageView;
import io.github.ahrimjang.mail.common.SendLogEntry;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import io.github.ahrimjang.mail.core.port.MailQueue;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Use cases for authoring and inspecting campaigns.
 *
 * <p>Creation is intentionally cheap: it persists the campaign and, for list
 * campaigns, hands one fan-out job to the worker (ad-hoc recipients are expanded
 * inline since they are bounded by the request body), then returns immediately.
 * The actual sending is done asynchronously by the worker — this decoupling is
 * what lets the API stay responsive under large recipient lists.
 */
@Service
public class CampaignService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CampaignService.class);

    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final EmailEventRepository events;
    private final MailQueue mailQueue;
    private final TemplateRepository templates;
    private final ContactRepository contacts;
    private final ContactListRepository lists;

    /** Who is acting, for which tenant — resolved by the API adapter per request. */
    private final WorkspaceContext ctx;
    private final PlanLimits planLimits;
    private final EmailVerificationService verification;
    private final EmailDraftService emailDrafts;
    private final SendingSuspensionService suspensionGuard;
    private final SenderPolicy senderPolicy;
    private final SendingWarmupService warmup;

    public CampaignService(CampaignRepository campaigns, MailMessageRepository messages, EmailEventRepository events,
                           MailQueue mailQueue, TemplateRepository templates, ContactRepository contacts,
                           ContactListRepository lists,
                           WorkspaceContext ctx, PlanLimits planLimits,
                           EmailVerificationService verification, EmailDraftService emailDrafts,
                           SendingSuspensionService suspensionGuard, SenderPolicy senderPolicy,
                           SendingWarmupService warmup) {
        this.ctx = ctx;
        this.campaigns = campaigns;
        this.messages = messages;
        this.events = events;
        this.mailQueue = mailQueue;
        this.templates = templates;
        this.contacts = contacts;
        this.lists = lists;
        this.planLimits = planLimits;
        this.verification = verification;
        this.emailDrafts = emailDrafts;
        this.suspensionGuard = suspensionGuard;
        this.senderPolicy = senderPolicy;
        this.warmup = warmup;
    }

    /** 워밍업 판정을 위한 이번 캠페인의 대상 수 — 리스트면 멤버 수, 애드혹이면 주소 수. */
    private long targetCountOf(CreateCampaignRequest request) {
        if (request.listId() != null) {
            return contacts.countByListId(request.listId());
        }
        return request.recipients() == null ? 0 : request.recipients().size();
    }

    public CampaignView create(CreateCampaignRequest request) {
        // 가입 이메일 소유 검증 전에는 발송 경로를 열지 않는다 (스팸 오남용 통로 차단)
        verification.assertCurrentUserVerified();
        // 평판 방어 — 바운스율 임계 초과로 정지된 워크스페이스는 신규 등록 불가
        suspensionGuard.assertNotSuspended(ctx.currentWorkspaceId());
        // SES 발신 도메인 제약 — 운영에서 허용 밖 발신 주소는 등록 시점에 거른다
        senderPolicy.assertSenderAllowed(request.senderEmail());
        senderPolicy.assertReplyToValid(request.replyTo());
        // 신규 워크스페이스 워밍업 — 첫 발송부터 대량으로 나가면 바운스율 정지가 늦는다
        long targetCount = targetCountOf(request);
        warmup.assertBatchAllowed(ctx.currentWorkspaceId(), targetCount);
        // 플랜의 월 발송량 한도 — 등록 시점에만 검사한다(발송 중 컷오프 금지,
        // 진행 중 캠페인은 끝까지). 그래서 "지금까지"가 아니라 "지금까지 + 이번 대상"으로
        // 예산을 본다. 정책: docs/BILLING-policy.md 4절.
        planLimits.assertCampaignRegistrationAllowed(ctx.currentWorkspaceId(), targetCount);
        // 플랜 기능 게이팅 — A/B·세그먼트는 요청 필드로 판정 (임시저장은 자유,
        // 발송 등록이 관문)
        planLimits.assertCampaignFeaturesAllowed(ctx.currentWorkspaceId(), request);
        String subject;
        String body;
        if (request.emailId() != null) {
            // 이메일(캠페인용 콘텐츠) 선택 — 템플릿과 마찬가지로 등록 시점 스냅샷
            var email = emailDrafts.ownedOrThrow(request.emailId());
            subject = email.getSubject();
            body = email.getHtmlBody();
        } else if (request.templateId() != null) {
            Template template = templates.findById(request.templateId())
                    .filter(this::templateVisible)
                    .orElseThrow(() -> new NoSuchElementException("template not found: " + request.templateId()));
            subject = template.getSubject();
            body = template.getHtmlBody();
        } else {
            subject = request.subject();
            body = request.body();
        }
        if (subject == null || subject.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("subject and body are required (direct or via template)");
        }

        // A future scheduledAt defers the queue release; null or past sends now.
        Instant now = Instant.now();
        boolean deferred = request.scheduledAt() != null && request.scheduledAt().isAfter(now);

        if (request.listId() != null) {
            lists.findById(request.listId())
                    .filter(l -> l.getWorkspaceId().equals(ctx.currentWorkspaceId()))
                    .orElseThrow(() -> new NoSuchElementException("list not found: " + request.listId()));
        }

        Campaign campaign = Campaign.draft(subject, body);
        campaign.setWorkspaceId(ctx.currentWorkspaceId());
        campaign.setCreatedBy(ctx.currentUserEmail());
        campaign.setStatus(CampaignStatus.QUEUED);
        campaign.setName(blankToNull(request.name()));
        campaign.setDescription(blankToNull(request.description()));
        campaign.setSenderName(blankToNull(request.senderName()));
        campaign.setSenderEmail(blankToNull(request.senderEmail()));
        campaign.setScheduledAt(request.scheduledAt());
        // Immediate campaigns are released right here; scheduled ones keep
        // enqueuedAt null so the worker's scheduler claims them when due.
        campaign.setEnqueuedAt(deferred ? null : now);
        campaign.setTemplateId(request.templateId());
        campaign.setReplyTo(blankToNull(request.replyTo()));
        campaign.setEmailId(request.emailId());   // 캠페인-이메일 매핑 (소프트 참조)
        campaign.setListId(request.listId());
        // Campaign period: engagement observed after endsAt is dropped, so the
        // reported rates cover a bounded window. Must leave room to send first.
        if (request.endsAt() != null) {
            Instant sendStart = deferred ? request.scheduledAt() : now;
            if (!request.endsAt().isAfter(sendStart)) {
                throw new IllegalArgumentException("endsAt must be after the send time");
            }
            campaign.setEndsAt(request.endsAt());
        }
        // Engagement segment: narrow the list to members whose open/click rate
        // clears the floors. Evaluated at fan-out time, so scheduled campaigns
        // use engagement as of the release, not as of authoring.
        if (request.segMinOpenPercent() != null || request.segMinClickPercent() != null) {
            if (request.listId() == null) {
                throw new IllegalArgumentException("engagement segment requires a listId");
            }
            campaign.setSegMinOpenPercent(requirePercent(request.segMinOpenPercent(), "segMinOpenPercent"));
            campaign.setSegMinClickPercent(requirePercent(request.segMinClickPercent(), "segMinClickPercent"));
        }
        // A/B split test: any non-blank B content makes this an A/B campaign.
        // Variant B mirrors the main content sourcing — direct subject/body or a
        // template snapshotted at create time.
        if (request.abEmailId() != null) {
            var abEmail = emailDrafts.ownedOrThrow(request.abEmailId());
            campaign.setAbSubjectB(abEmail.getSubject());
            campaign.setAbBodyB(abEmail.getHtmlBody());
        } else if (request.abTemplateId() != null) {
            Template abTemplate = templates.findById(request.abTemplateId())
                    .filter(this::templateVisible)
                    .orElseThrow(() -> new NoSuchElementException("template not found: " + request.abTemplateId()));
            campaign.setAbSubjectB(abTemplate.getSubject());
            campaign.setAbBodyB(abTemplate.getHtmlBody());
        } else {
            campaign.setAbSubjectB(blankToNull(request.abSubjectB()));
            campaign.setAbBodyB(blankToNull(request.abBodyB()));
        }
        if (campaign.isAbTest()) {
            int split = request.abSplitPercent() == null ? 50 : request.abSplitPercent();
            if (split < 1 || split > 99) {
                throw new IllegalArgumentException("abSplitPercent must be between 1 and 99");
            }
            campaign.setAbSplitPercent(split);
        }
        // Winner flow: only abTestPercent% of the audience gets the test; the rest
        // waits and later receives the variant that performed better on the metric.
        if (request.abTestPercent() != null) {
            if (!campaign.isAbTest()) {
                throw new IllegalArgumentException("abTestPercent requires A/B content");
            }
            int testPercent = request.abTestPercent();
            if (testPercent < 5 || testPercent > 90) {
                throw new IllegalArgumentException("abTestPercent must be between 5 and 90");
            }
            campaign.setAbTestPercent(testPercent);
            String metric = request.abEvalMetric() == null ? "OPEN" : request.abEvalMetric().toUpperCase();
            if (!"OPEN".equals(metric) && !"CLICK".equals(metric)) {
                throw new IllegalArgumentException("abEvalMetric must be OPEN or CLICK");
            }
            int wait = request.abEvalWaitMinutes() == null ? 60 : request.abEvalWaitMinutes();
            if (wait < 1) {
                throw new IllegalArgumentException("abEvalWaitMinutes must be at least 1");
            }
            // 테스트군이 안별 최소 표본에 못 미치면 승자 플로우를 받지 않는다(ARCH-7).
            // 판정 쪽(AbWinnerService)이 같은 하한으로 유예하므로, 여기서 안 막으면 그 캠페인은
            // 24시간 유예 끝에 약한 근거로 확정되는 길밖에 없다 — 등록 때 말해주는 게 낫다.
            long testGroup = targetCount * testPercent / 100;
            long perVariant = testGroup / 2;
            if (perVariant < AbWinnerService.MIN_SENT_PER_VARIANT) {
                throw new IllegalArgumentException(String.format(
                        "승자 자동 발송은 테스트 그룹이 안별로 최소 %d명은 돼야 해요. 지금 대상 %,d명의 %d%%는 %,d명(안별 %,d명)이에요 — "
                                + "테스트 비율을 올리거나 대상을 늘리거나, 승자 자동 발송 없이 제목 A/B(반반)로 보내주세요.",
                        AbWinnerService.MIN_SENT_PER_VARIANT, targetCount, testPercent, testGroup, perVariant));
            }
            campaign.setAbEvalMetric(metric);
            campaign.setAbEvalWaitMinutes(wait);
        }
        // 대상 검증은 저장보다 앞에 — 저장 뒤에 실패하면 잡 없는 QUEUED 행(고아)이 남는다(ARCH-5).
        // 스위퍼가 걷어내긴 하지만, 애초에 만들지 않는 게 먼저다.
        if (request.listId() != null) {
            if (contacts.countByListId(request.listId()) == 0) {
                throw new IllegalArgumentException("list has no members: " + request.listId());
            }
        } else if (request.recipients() == null || request.recipients().isEmpty()) {
            throw new IllegalArgumentException("recipients must not be empty");
        }
        Campaign saved = campaigns.save(campaign);

        if (request.listId() != null) {
            // Large list campaigns fan out asynchronously: persist only the campaign
            // and hand a single fan-out job to the worker, so create() is O(1) in the
            // recipient count. Scheduled campaigns publish the fan-out job at release
            // time (see CampaignScheduleService); immediate ones publish it now.
            if (!deferred) {
                mailQueue.enqueueFanout(saved.getId());
            }
        } else {
            // 배달 불가가 확실한 주소는 큐에 넣지 않는다 — 바운스는 사후 복구가 안 된다
            var unsendable = request.recipients().stream()
                    .filter(r -> !EmailAddressValidator.isSendable(r))
                    .limit(5)
                    .toList();
            if (!unsendable.isEmpty()) {
                throw new IllegalArgumentException(
                        "보낼 수 없는 주소가 있어요: " + String.join(", ", unsendable)
                                + (request.recipients().size() > 5 ? " …" : "")
                                + " — 형식을 확인하거나 목록에서 빼주세요.");
            }
            // Ad-hoc recipient lists are bounded by the request body — expand inline.
            List<MailMessage> queued = request.recipients().stream()
                    .map(recipient -> {
                        MailMessage m = MailMessage.queued(saved.getId(), recipient);
                        if (saved.isAbTest()) {
                            m.setVariant(saved.hasWinnerFlow()
                                    ? AbVariantAssigner.assignWithHoldout(recipient,
                                            saved.getAbTestPercent(), saved.getAbSplitPercent())
                                    : AbVariantAssigner.assign(recipient, saved.getAbSplitPercent()));
                        }
                        return m;
                    })
                    .toList();
            List<MailMessage> savedMessages = messages.saveAll(queued);
            if (!deferred) {
                // Winner flow only releases the test batch: held rows (variant null)
                // stay PENDING until the winner is decided.
                savedMessages.stream()
                        .filter(m -> !saved.hasWinnerFlow() || m.getVariant() != null)
                        .forEach(m -> mailQueue.enqueue(m.getId()));
                if (saved.hasWinnerFlow()) {
                    campaigns.scheduleAbEvaluation(saved.getId(),
                            now.plus(Duration.ofMinutes(saved.getAbEvalWaitMinutes())));
                }
            }
        }

        return toView(saved);
    }

    /** A campaign of another tenant reads as absent, never as forbidden. */
    private boolean owned(Campaign campaign) {
        return campaign.getWorkspaceId() != null
                && campaign.getWorkspaceId().equals(ctx.currentWorkspaceId());
    }

    /** Built-ins (workspace null) are usable by everyone; user templates only by their tenant. */
    private boolean templateVisible(Template template) {
        return template.getWorkspaceId() == null
                || template.getWorkspaceId().equals(ctx.currentWorkspaceId());
    }

    /** Null passes through (no floor on that metric); otherwise must be 1..100. */
    private static Integer requirePercent(Integer percent, String field) {
        if (percent == null) {
            return null;
        }
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException(field + " must be between 1 and 100");
        }
        return percent;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public CampaignView get(Long id) {
        Campaign campaign = campaigns.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        return toView(campaign);
    }

    /**
     * Persist the compose form as a DRAFT: nothing is queued or fanned out and
     * only a minimal identity check applies (send-time validation happens when
     * the draft is actually launched through {@link #create}). Ad-hoc recipients
     * are kept newline-joined on the row — no message rows exist yet.
     */
    public CampaignView saveDraft(CreateCampaignRequest request) {
        Campaign draft = Campaign.draft(orEmpty(request.subject()), orEmpty(request.body()));
        draft.setWorkspaceId(ctx.currentWorkspaceId());
        draft.setCreatedBy(ctx.currentUserEmail());
        applyDraftFields(draft, request);
        return toView(campaigns.save(draft));
    }

    /** Overwrite a DRAFT with the form's current state. */
    public CampaignView updateDraft(Long id, CreateCampaignRequest request) {
        Campaign draft = requireDraft(id);
        draft.setSubject(orEmpty(request.subject()));
        draft.setBody(orEmpty(request.body()));
        applyDraftFields(draft, request);
        return toView(campaigns.save(draft));
    }

    /** The editable fields of a DRAFT, for the compose form to resume from. */
    public CampaignDraftView draft(Long id) {
        Campaign d = requireDraft(id);
        return new CampaignDraftView(
                d.getId(), d.getName(), d.getDescription(),
                blankToNull(d.getSubject()), blankToNull(d.getBody()),
                d.getTemplateId(),
                d.getDraftRecipients() == null ? List.of() : List.of(d.getDraftRecipients().split("\n")),
                d.getListId(), d.getSenderName(), d.getSenderEmail(), d.getReplyTo(),
                d.getScheduledAt(), d.getEndsAt(),
                d.getAbSubjectB(), d.getAbBodyB(),
                d.getAbTestPercent(), d.getAbEvalMetric(), d.getAbEvalWaitMinutes(),
                d.getSegMinOpenPercent(), d.getSegMinClickPercent());
    }

    /** Discard a DRAFT (also called after launching it as a real campaign). */
    public void deleteDraft(Long id) {
        requireDraft(id);
        campaigns.deleteById(id);
    }

    private void applyDraftFields(Campaign draft, CreateCampaignRequest request) {
        boolean anyIdentity = blankToNull(request.name()) != null
                || blankToNull(request.subject()) != null
                || request.templateId() != null;
        if (!anyIdentity) {
            throw new IllegalArgumentException("draft needs at least a name, subject, or template");
        }
        draft.setName(blankToNull(request.name()));
        draft.setDescription(blankToNull(request.description()));
        draft.setSenderName(blankToNull(request.senderName()));
        draft.setSenderEmail(blankToNull(request.senderEmail()));
        draft.setScheduledAt(request.scheduledAt());
        draft.setEndsAt(request.endsAt());
        draft.setTemplateId(request.templateId());
        draft.setReplyTo(blankToNull(request.replyTo()));
        draft.setEmailId(request.emailId());
        draft.setListId(request.listId());
        draft.setSegMinOpenPercent(request.segMinOpenPercent());
        draft.setSegMinClickPercent(request.segMinClickPercent());
        draft.setAbSubjectB(blankToNull(request.abSubjectB()));
        draft.setAbBodyB(blankToNull(request.abBodyB()));
        draft.setAbTestPercent(request.abTestPercent());
        draft.setAbEvalMetric(request.abEvalMetric());
        draft.setAbEvalWaitMinutes(request.abEvalWaitMinutes());
        draft.setDraftRecipients(request.recipients() == null || request.recipients().isEmpty()
                ? null
                : String.join("\n", request.recipients()));
    }

    private Campaign requireDraft(Long id) {
        Campaign campaign = campaigns.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new IllegalStateException("campaign is not a draft: " + id);
        }
        return campaign;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public List<CampaignView> list() {
        // 캠페인 수와 무관한 쿼리 수로 한꺼번에 조립 — 목록 화면이 5초마다 이걸 부른다
        return toViews(campaigns.findByWorkspace(ctx.currentWorkspaceId()));
    }

    /**
     * Cancels a scheduled campaign that has not been released to the queue yet.
     * The conditional-update claim races the worker's scheduler on the same row,
     * so exactly one of "released" / "canceled" happens — a message can never be
     * both sent and canceled. Losing the race (already released, or the campaign
     * was immediate) is an {@link IllegalStateException} for the caller to map
     * to a conflict response.
     */
    public CampaignView cancelSchedule(Long id) {
        campaigns.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        if (!campaigns.claimForCancel(id)) {
            throw new IllegalStateException("campaign already released or not cancellable: " + id);
        }
        // Safe after winning the claim: these rows were never published.
        messages.cancelPendingByCampaign(id);
        return get(id);
    }

    /**
     * 발송 중 중단(ARCH-8). 오발송을 알아챈 순간 남은 발송을 멈추는 유일한 수단이다 —
     * 예전엔 예약 취소만 있어 즉시 캠페인은 100만 통이 다 나갈 때까지 멈출 코드가 없었다.
     *
     * <p>순서가 중요하다: 먼저 캠페인을 CANCELED 로 조건부 전이(이겨야 진행) → PENDING
     * 메시지 일괄 취소. 그 뒤 팬아웃 루프는 페이지마다 상태를 보고 멈추고, 디스패치는 취소된
     * 메시지 잡을 종료 상태라 건너뛴다. 이미 claim 돼 SMTP 로 넘어간 몇 통(워커 동시성만큼)은
     * 회수할 수 없다 — 그건 화면에도 그렇게 말한다.
     */
    public CampaignView abort(Long id) {
        campaigns.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        if (!campaigns.abort(id)) {
            throw new IllegalStateException("이미 끝났거나 취소된 캠페인이라 중단할 수 없어요: " + id);
        }
        int canceled = messages.cancelPendingByCampaign(id);
        log.warn("캠페인 {} 발송 중단 — 남은 {}건 취소 (요청자 {})", id, canceled, ctx.currentUserEmail());
        return get(id);
    }

    /** This campaign's clicked links, best first (tracked click URLs from the event stream). */
    public List<LinkClicksView> linkClicks(Long id, int limit) {
        campaigns.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        int capped = Math.max(1, Math.min(50, limit));
        return events.linkClicksByCampaign(id, capped).stream()
                .map(l -> new LinkClicksView(l.url(), l.clicks(), l.uniqueMessages()))
                .toList();
    }

    /** The mail this campaign sends (subject + raw HTML body snapshot, A/B variant B included). */
    public CampaignContentView content(Long id) {
        Campaign campaign = campaigns.findById(id)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        return new CampaignContentView(campaign.getSubject(), campaign.getBody(),
                campaign.getAbSubjectB(), campaign.getAbBodyB());
    }

    /** Recent per-recipient deliveries of a campaign, newest first (drill-down feed). */
    public List<MessageView> recentMessages(Long campaignId, int limit) {
        campaigns.findById(campaignId)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + campaignId));
        int capped = Math.max(1, Math.min(limit, 200));
        return messages.findRecentByCampaign(campaignId, capped).stream()
                .map(m -> new MessageView(m.getId(), m.getRecipient(), m.getStatus(),
                        m.getErrorMessage(), m.getUpdatedAt()))
                .toList();
    }

    /**
     * Aggregated send log: state changes grouped into fixed time buckets per status
     * ("N sent", "M bounced — reason"), newest first. Bounded output regardless of
     * campaign size — this is what the detail page renders.
     */
    public List<SendLogEntry> sendLog(Long campaignId, int bucketSeconds, int limit) {
        campaigns.findById(campaignId)
                .filter(this::owned)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + campaignId));
        int bucket = Math.max(1, Math.min(bucketSeconds, 3600));
        int capped = Math.max(1, Math.min(limit, 200));
        return messages.aggregateLogByCampaign(campaignId, bucket, capped).stream()
                .map(b -> new SendLogEntry(b.bucketStart(), b.status(), b.count(), b.sampleError()))
                .toList();
    }

    /** 끝난 캠페인의 상태 개수가 더는 안 바뀐다고 보는 대기 시간 — 복구 스위퍼(10분)와 지연 종료의 여유. */
    static final java.time.Duration COUNT_SETTLE = java.time.Duration.ofMinutes(30);

    private CampaignView toView(Campaign campaign) {
        return toViews(List.of(campaign)).get(0);
    }

    /**
     * 캠페인 뷰 조립 — 집계를 캠페인 수와 무관한 쿼리 수로 모은다. 이전엔 캠페인마다 상태 COUNT 7개와
     * 오픈·클릭 count(distinct) 2개였고, 목록 화면이 5초마다 전 캠페인에 대해 돌렸다.
     * <ul>
     *   <li>상태 개수: 끝나고 {@link #COUNT_SETTLE} 지난 캠페인은 저장된 스냅샷, 나머지는 GROUP BY 1회</li>
     *   <li>오픈·클릭: 프로젝션이 첫 참여 때만 올리는 카운터를 1회에 읽는다</li>
     *   <li>이름: 같은 템플릿·이메일·리스트는 한 번만 조회</li>
     * </ul>
     */
    private List<CampaignView> toViews(List<Campaign> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        java.time.Instant now = java.time.Instant.now();
        List<Long> ids = list.stream().map(Campaign::getId).toList();
        java.util.Set<Long> settled = list.stream()
                .filter(c -> countsSettled(c, now))
                .map(Campaign::getId)
                .collect(java.util.stream.Collectors.toSet());

        java.util.Map<Long, MessageCounts> counts = new java.util.HashMap<>();
        java.util.Map<Long, MailMessageRepository.CountSnapshot> snapshots =
                settled.isEmpty() ? java.util.Map.of() : messages.findCountSnapshots(settled);
        snapshots.forEach((id, s) -> {
            if (s.counts() != null) {
                counts.put(id, s.counts());
            }
        });
        List<Long> live = ids.stream().filter(id -> !counts.containsKey(id)).toList();
        if (!live.isEmpty()) {
            java.util.Map<Long, MessageCounts> fresh = messages.countByCampaigns(live);
            for (Long id : live) {
                MessageCounts c = fresh.getOrDefault(id, MessageCounts.EMPTY);
                counts.put(id, c);
                if (settled.contains(id)) {
                    // 조건부 저장 — 세는 사이 늦은 바운스가 무효화했으면 져서 옛 숫자를 남기지 않는다
                    MailMessageRepository.CountSnapshot prior = snapshots.get(id);
                    messages.saveCountSnapshot(id, prior == null ? null : prior.version(), c, now);
                }
            }
        }

        java.util.Map<Long, List<EmailEventRepository.CampaignEngagement>> engagement =
                events.engagementByCampaigns(ids).stream()
                        .collect(java.util.stream.Collectors.groupingBy(EmailEventRepository.CampaignEngagement::campaignId));

        java.util.Map<Long, String> templateNames = new java.util.HashMap<>();
        java.util.Map<Long, String> emailNames = new java.util.HashMap<>();
        java.util.Map<Long, String> listNames = new java.util.HashMap<>();
        return list.stream()
                .map(c -> assemble(c, counts.get(c.getId()), engagement.getOrDefault(c.getId(), List.of()),
                        templateNames, emailNames, listNames))
                .toList();
    }

    /** 완료·중단된 지 {@link #COUNT_SETTLE} 이 지나 상태 개수가 굳었다고 보는 캠페인. */
    private static boolean countsSettled(Campaign c, java.time.Instant now) {
        return (c.getStatus() == io.github.ahrimjang.mail.common.CampaignStatus.COMPLETED
                || c.getStatus() == io.github.ahrimjang.mail.common.CampaignStatus.CANCELED)
                && c.getCompletedAt() != null
                && c.getCompletedAt().isBefore(now.minus(COUNT_SETTLE));
    }

    /** 한 번의 뷰 조립 안에서 같은 키는 한 번만 읽는다 — 없는 이름(null)도 기억한다. */
    private static <V> V memo(java.util.Map<Long, V> cache, Long key, java.util.function.Function<Long, V> load) {
        if (!cache.containsKey(key)) {
            cache.put(key, load.apply(key));
        }
        return cache.get(key);
    }

    private CampaignView assemble(Campaign campaign, MessageCounts counts,
                                  List<EmailEventRepository.CampaignEngagement> engagement,
                                  java.util.Map<Long, String> templateNames,
                                  java.util.Map<Long, String> emailNames,
                                  java.util.Map<Long, String> listNames) {
        long opened = engagement.stream().mapToLong(EmailEventRepository.CampaignEngagement::opened).sum();
        long clicked = engagement.stream().mapToLong(EmailEventRepository.CampaignEngagement::clicked).sum();
        // Soft references: a deleted template/list leaves the id without a name.
        String templateName = campaign.getTemplateId() == null ? null
                : memo(templateNames, campaign.getTemplateId(),
                        id -> templates.findById(id).map(Template::getName).orElse(null));
        String emailName = campaign.getEmailId() == null ? null
                : memo(emailNames, campaign.getEmailId(), emailDrafts::displayNameOf);
        String listName = campaign.getListId() == null ? null
                : memo(listNames, campaign.getListId(), id -> lists.findById(id)
                        .map(io.github.ahrimjang.mail.core.domain.ContactList::getName).orElse(null));
        return new CampaignView(
                campaign.getId(),
                campaign.getName(),
                campaign.getDescription(),
                campaign.getSubject(),
                campaign.getStatus(),
                counts.total(),
                counts.pending(),
                counts.sent(),
                counts.failed(),
                counts.bounced(),
                counts.suppressed(),
                opened,
                clicked,
                campaign.getCreatedAt(),
                campaign.getSenderName(),
                campaign.getSenderEmail(),
                campaign.getScheduledAt(),
                campaign.getEnqueuedAt(), campaign.getCompletedAt(),
                campaign.getEndsAt(),
                campaign.getCreatedBy(),
                campaign.getTemplateId(),
                templateName,
                campaign.getEmailId(),
                emailName,
                campaign.getListId(),
                listName,
                campaign.getSegMinOpenPercent(),
                campaign.getSegMinClickPercent(),
                campaign.getAbTestPercent(),
                campaign.getAbEvalMetric(),
                campaign.getAbWinner(),
                campaign.getAbEvaluateAt(),
                variantStats(campaign, engagement)
        );
    }

    /**
     * Per-variant delivery + engagement rows of an A/B campaign; null for plain ones.
     * 오픈·클릭은 이미 읽어 온 카운터 줄에서 안별로 꺼낸다 — 안마다 쿼리를 더 날리지 않는다.
     */
    private List<CampaignView.VariantStats> variantStats(Campaign campaign,
                                                         List<EmailEventRepository.CampaignEngagement> engagement) {
        if (!campaign.isAbTest()) {
            return null;
        }
        java.util.Map<String, EmailEventRepository.CampaignEngagement> byVariant = new java.util.HashMap<>();
        for (EmailEventRepository.CampaignEngagement e : engagement) {
            if (e.variant() != null) {
                byVariant.put(e.variant(), e);
            }
        }
        return messages.countByCampaignAndVariant(campaign.getId()).stream()
                .map(v -> {
                    EmailEventRepository.CampaignEngagement e = byVariant.get(v.variant());
                    return new CampaignView.VariantStats(v.variant(), v.total(), v.sent(),
                            e == null ? 0 : e.opened(), e == null ? 0 : e.clicked());
                })
                .toList();
    }
}
