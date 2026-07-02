package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.CreateCampaignRequest;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import io.github.ahrimjang.mail.core.port.MailQueue;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.springframework.stereotype.Service;

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

    public CampaignService(CampaignRepository campaigns, MailMessageRepository messages, EmailEventRepository events,
                           MailQueue mailQueue, TemplateRepository templates, ContactRepository contacts) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.events = events;
        this.mailQueue = mailQueue;
        this.templates = templates;
        this.contacts = contacts;
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

        Campaign campaign = Campaign.draft(subject, body);
        campaign.setStatus(CampaignStatus.QUEUED);
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
        savedMessages.forEach(m -> mailQueue.enqueue(m.getId()));

        return toView(saved);
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

    private CampaignView toView(Campaign campaign) {
        MessageCounts counts = messages.countByCampaign(campaign.getId());
        long opened = events.countDistinctMessages(campaign.getId(), EventType.OPEN);
        long clicked = events.countDistinctMessages(campaign.getId(), EventType.CLICK);
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
                campaign.getCreatedAt()
        );
    }
}
