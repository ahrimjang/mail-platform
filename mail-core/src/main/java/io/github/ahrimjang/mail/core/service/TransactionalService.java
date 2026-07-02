package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.TransactionalRequest;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Use case for one-off transactional sends (welcome mail, receipts, ...).
 *
 * <p>The template is rendered with the request variables immediately, then
 * wrapped in a single-recipient campaign so the whole existing pipeline —
 * queue, tracking, suppression, metrics — is reused unchanged.
 */
@Service
public class TransactionalService {

    private final TemplateRepository templates;
    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final MailQueue mailQueue;
    private final TemplateRenderer renderer;

    public TransactionalService(TemplateRepository templates, CampaignRepository campaigns,
                                MailMessageRepository messages, MailQueue mailQueue, TemplateRenderer renderer) {
        this.templates = templates;
        this.campaigns = campaigns;
        this.messages = messages;
        this.mailQueue = mailQueue;
        this.renderer = renderer;
    }

    /**
     * Render the template with the request variables and enqueue a
     * single-recipient campaign. Returns the created campaign's id.
     */
    public Long send(TransactionalRequest request) {
        if (request.recipient() == null || !request.recipient().contains("@")) {
            throw new IllegalArgumentException("valid recipient is required");
        }
        Template template = templates.findById(request.templateId())
                .orElseThrow(() -> new NoSuchElementException("template not found: " + request.templateId()));
        Map<String, String> vars = request.variables() == null ? Map.of() : request.variables();

        Campaign campaign = Campaign.draft(
                renderer.render(template.getSubject(), vars),
                renderer.render(template.getHtmlBody(), vars));
        campaign.setStatus(CampaignStatus.QUEUED);
        Campaign saved = campaigns.save(campaign);

        List<MailMessage> savedMessages = messages.saveAll(
                List.of(MailMessage.queued(saved.getId(), request.recipient())));
        savedMessages.forEach(m -> mailQueue.enqueue(m.getId()));

        return saved.getId();
    }
}
