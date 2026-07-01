package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.CreateCampaignRequest;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Use cases for authoring and inspecting campaigns.
 *
 * <p>Creation is intentionally cheap: it persists the campaign and fans the
 * recipient list out into PENDING queue rows, then returns immediately. The
 * actual sending is done asynchronously by the worker — this decoupling is
 * what lets the API stay responsive under large recipient lists.
 */
@Service
public class CampaignService {

    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final EmailEventRepository events;

    public CampaignService(CampaignRepository campaigns, MailMessageRepository messages, EmailEventRepository events) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.events = events;
    }

    public CampaignView create(CreateCampaignRequest request) {
        if (request.recipients() == null || request.recipients().isEmpty()) {
            throw new IllegalArgumentException("recipients must not be empty");
        }

        Campaign campaign = Campaign.draft(request.subject(), request.body());
        campaign.setStatus(CampaignStatus.QUEUED);
        Campaign saved = campaigns.save(campaign);

        List<MailMessage> queued = request.recipients().stream()
                .map(recipient -> MailMessage.queued(saved.getId(), recipient))
                .toList();
        messages.saveAll(queued);

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
