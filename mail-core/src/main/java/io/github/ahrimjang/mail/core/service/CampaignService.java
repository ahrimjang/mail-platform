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

    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final EmailEventRepository events;
    private final MailQueue mailQueue;
    private final TemplateRepository templates;
    private final ContactRepository contacts;
    private final ContactListRepository lists;

    /** Who is acting, for which tenant — resolved by the API adapter per request. */
    private final WorkspaceContext ctx;

    public CampaignService(CampaignRepository campaigns, MailMessageRepository messages, EmailEventRepository events,
                           MailQueue mailQueue, TemplateRepository templates, ContactRepository contacts,
                           ContactListRepository lists,
                           WorkspaceContext ctx) {
        this.ctx = ctx;
        this.campaigns = campaigns;
        this.messages = messages;
        this.events = events;
        this.mailQueue = mailQueue;
        this.templates = templates;
        this.contacts = contacts;
        this.lists = lists;
    }

    public CampaignView create(CreateCampaignRequest request) {
        String subject;
        String body;
        if (request.templateId() != null) {
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
        if (request.abTemplateId() != null) {
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
            campaign.setAbEvalMetric(metric);
            campaign.setAbEvalWaitMinutes(wait);
        }
        Campaign saved = campaigns.save(campaign);

        if (request.listId() != null) {
            // Large list campaigns fan out asynchronously: persist only the campaign
            // and hand a single fan-out job to the worker, so create() is O(1) in the
            // recipient count. Scheduled campaigns publish the fan-out job at release
            // time (see CampaignScheduleService); immediate ones publish it now.
            if (contacts.countByListId(request.listId()) == 0) {
                throw new IllegalArgumentException("list has no members: " + request.listId());
            }
            if (!deferred) {
                mailQueue.enqueueFanout(saved.getId());
            }
        } else {
            if (request.recipients() == null || request.recipients().isEmpty()) {
                throw new IllegalArgumentException("recipients must not be empty");
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
                d.getListId(), d.getSenderName(), d.getSenderEmail(),
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
        return campaigns.findByWorkspace(ctx.currentWorkspaceId()).stream()
                .map(this::toView)
                .toList();
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

    private CampaignView toView(Campaign campaign) {
        MessageCounts counts = messages.countByCampaign(campaign.getId());
        long opened = events.countDistinctMessages(campaign.getId(), EventType.OPEN);
        long clicked = events.countDistinctMessages(campaign.getId(), EventType.CLICK);
        // Soft references: a deleted template/list leaves the id without a name.
        String templateName = campaign.getTemplateId() == null ? null
                : templates.findById(campaign.getTemplateId()).map(Template::getName).orElse(null);
        String listName = campaign.getListId() == null ? null
                : lists.findById(campaign.getListId())
                        .map(io.github.ahrimjang.mail.core.domain.ContactList::getName).orElse(null);
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
                campaign.getListId(),
                listName,
                campaign.getSegMinOpenPercent(),
                campaign.getSegMinClickPercent(),
                campaign.getAbTestPercent(),
                campaign.getAbEvalMetric(),
                campaign.getAbWinner(),
                campaign.getAbEvaluateAt(),
                variantStats(campaign)
        );
    }

    /** Per-variant delivery + engagement rows of an A/B campaign; null for plain ones. */
    private List<CampaignView.VariantStats> variantStats(Campaign campaign) {
        if (!campaign.isAbTest()) {
            return null;
        }
        return messages.countByCampaignAndVariant(campaign.getId()).stream()
                .map(v -> new CampaignView.VariantStats(
                        v.variant(),
                        v.total(),
                        v.sent(),
                        events.countDistinctMessagesByVariant(campaign.getId(), EventType.OPEN, v.variant()),
                        events.countDistinctMessagesByVariant(campaign.getId(), EventType.CLICK, v.variant())))
                .toList();
    }
}
