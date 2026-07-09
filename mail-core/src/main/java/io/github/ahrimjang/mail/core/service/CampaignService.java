package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignContentView;
import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.CreateCampaignRequest;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageView;
import io.github.ahrimjang.mail.common.SendLogEntry;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import io.github.ahrimjang.mail.core.port.MailQueue;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Use cases for authoring and inspecting campaigns.
 *
 * <p>Creation is intentionally cheap: it persists the campaign, fans the
 * recipient list out into PENDING queue rows, enqueues one send job per row,
 * then returns immediately. The actual sending is done asynchronously by the
 * worker — this decoupling is what lets the API stay responsive under large
 * recipient lists.
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

    public CampaignService(CampaignRepository campaigns, MailMessageRepository messages, EmailEventRepository events,
                           MailQueue mailQueue, TemplateRepository templates, ContactRepository contacts,
                           ContactListRepository lists) {
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

        Campaign campaign = Campaign.draft(subject, body);
        campaign.setStatus(CampaignStatus.QUEUED);
        campaign.setSenderName(blankToNull(request.senderName()));
        campaign.setSenderEmail(blankToNull(request.senderEmail()));
        campaign.setScheduledAt(request.scheduledAt());
        // Immediate campaigns are released right here; scheduled ones keep
        // enqueuedAt null so the worker's scheduler claims them when due.
        campaign.setEnqueuedAt(deferred ? null : now);
        campaign.setTemplateId(request.templateId());
        campaign.setListId(request.listId());
        Campaign saved = campaigns.save(campaign);

        List<MailMessage> queued;
        if (request.listId() != null) {
            List<Contact> members = contacts.findByListId(request.listId());
            if (members.isEmpty()) {
                throw new IllegalArgumentException("list has no members: " + request.listId());
            }
            queued = members.stream()
                    .map(c -> MailMessage.queued(saved.getId(), c.getEmail(), c.getId()))
                    .toList();
        } else {
            if (request.recipients() == null || request.recipients().isEmpty()) {
                throw new IllegalArgumentException("recipients must not be empty");
            }
            queued = request.recipients().stream()
                    .map(recipient -> MailMessage.queued(saved.getId(), recipient))
                    .toList();
        }
        List<MailMessage> savedMessages = messages.saveAll(queued);
        if (!deferred) {
            savedMessages.forEach(m -> mailQueue.enqueue(m.getId()));
        }

        return toView(saved);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public CampaignView get(Long id) {
        Campaign campaign = campaigns.findById(id)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        return toView(campaign);
    }

    public List<CampaignView> list() {
        return campaigns.findAll().stream()
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
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        if (!campaigns.claimForCancel(id)) {
            throw new IllegalStateException("campaign already released or not cancellable: " + id);
        }
        // Safe after winning the claim: these rows were never published.
        messages.cancelPendingByCampaign(id);
        return get(id);
    }

    /** The mail this campaign sends (subject + raw HTML body snapshot). */
    public CampaignContentView content(Long id) {
        Campaign campaign = campaigns.findById(id)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        return new CampaignContentView(campaign.getSubject(), campaign.getBody());
    }

    /** Recent per-recipient deliveries of a campaign, newest first (drill-down feed). */
    public List<MessageView> recentMessages(Long campaignId, int limit) {
        campaigns.findById(campaignId)
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
                campaign.getTemplateId(),
                templateName,
                campaign.getListId(),
                listName
        );
    }
}
