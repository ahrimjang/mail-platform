package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import io.github.ahrimjang.mail.core.domain.Suppression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drains the send queue in batches. Invoked repeatedly by the worker's poller.
 *
 * <p>One batch = claim N pending messages, send each via the {@link MailSender}
 * port, and record the per-message outcome. Campaigns flip to SENDING on first
 * progress and to COMPLETED once their queue is fully drained.
 */
@Service
public class MailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MailDispatchService.class);

    private final MailMessageRepository messages;
    private final CampaignRepository campaigns;
    private final MailSender sender;
    private final SuppressionRepository suppressions;
    private final TrackingRewriter trackingRewriter;
    private final String baseUrl;

    public MailDispatchService(MailMessageRepository messages,
                               CampaignRepository campaigns,
                               MailSender sender,
                               SuppressionRepository suppressions,
                               TrackingRewriter trackingRewriter,
                               @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.messages = messages;
        this.campaigns = campaigns;
        this.sender = sender;
        this.suppressions = suppressions;
        this.trackingRewriter = trackingRewriter;
        this.baseUrl = baseUrl;
    }

    /**
     * Process up to {@code batchSize} pending messages.
     *
     * @return the number of messages handled (0 means the queue was empty)
     */
    public int dispatchBatch(int batchSize) {
        List<MailMessage> batch = messages.findPending(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        Set<Long> touchedCampaigns = new HashSet<>();
        for (MailMessage message : batch) {
            Campaign campaign = campaigns.findById(message.getCampaignId()).orElse(null);
            if (campaign == null) {
                message.markFailed("campaign no longer exists");
                messages.save(message);
                continue;
            }

            markSending(campaign);

            if (suppressions.existsByEmail(message.getRecipient())) {
                message.markSuppressed();
                messages.save(message);
                touchedCampaigns.add(campaign.getId());
                continue;
            }

            String trackedBody = trackingRewriter.rewriteLinks(campaign.getBody(), message.getTrackingToken(), baseUrl);
            String html = trackedBody + unsubscribeFooter(message.getUnsubToken())
                    + trackingRewriter.openPixel(message.getTrackingToken(), baseUrl);
            try {
                sender.send(message.getRecipient(), campaign.getSubject(), html);
                message.markSent();
            } catch (Exception e) {
                log.warn("send failed: campaign={} recipient={} reason={}",
                        campaign.getId(), message.getRecipient(), e.getMessage());
                message.markBounced(e.getMessage());
                suppressions.save(Suppression.of(message.getRecipient(), "bounce"));
            }
            messages.save(message);
            touchedCampaigns.add(campaign.getId());
        }

        touchedCampaigns.forEach(this::completeIfDrained);
        log.info("dispatched {} message(s)", batch.size());
        return batch.size();
    }

    private String unsubscribeFooter(String token) {
        return "<hr><p style=\"font-size:12px;color:#888\">더 이상 받지 않으려면 "
                + "<a href=\"" + baseUrl + "/api/unsubscribe/" + token + "\">수신거부</a></p>";
    }

    private void markSending(Campaign campaign) {
        if (campaign.getStatus() != CampaignStatus.SENDING) {
            campaign.setStatus(CampaignStatus.SENDING);
            campaigns.updateStatus(campaign.getId(), CampaignStatus.SENDING);
        }
    }

    private void completeIfDrained(Long campaignId) {
        MessageCounts counts = messages.countByCampaign(campaignId);
        if (counts.pending() == 0) {
            campaigns.updateStatus(campaignId, CampaignStatus.COMPLETED);
        }
    }
}
