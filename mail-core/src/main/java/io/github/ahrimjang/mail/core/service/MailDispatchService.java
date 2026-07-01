package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.MessageStatus;
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

/**
 * Sends one queued message per invocation. Invoked by the worker's queue listener.
 *
 * <p>One call = load the message by id, send it via the {@link MailSender}
 * port, and record the outcome. Campaigns flip to SENDING on first progress
 * and to COMPLETED once their queue is fully drained. Because the queue is
 * at-least-once, the handler is idempotent: a message that is no longer
 * PENDING is skipped.
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
     * Process a single queued message by id.
     *
     * <p>Idempotent: a missing message, or one that is no longer PENDING (a
     * redelivery or an already-processed row), is skipped without effect.
     */
    public void dispatchOne(Long messageId) {
        MailMessage message = messages.findById(messageId).orElse(null);
        if (message == null) {
            return;
        }
        if (message.getStatus() != MessageStatus.PENDING) {
            return;   // idempotency: skip redelivered/processed
        }
        Campaign campaign = campaigns.findById(message.getCampaignId()).orElse(null);
        if (campaign == null) {
            message.markFailed("campaign no longer exists");
            messages.save(message);
            return;
        }
        markSending(campaign);
        if (suppressions.existsByEmail(message.getRecipient())) {
            message.markSuppressed();
            messages.save(message);
            completeIfDrained(campaign.getId());
            return;
        }
        String trackedBody = trackingRewriter.rewriteLinks(campaign.getBody(), message.getTrackingToken(), baseUrl);
        String html = trackedBody + unsubscribeFooter(message.getUnsubToken())
                + trackingRewriter.openPixel(message.getTrackingToken(), baseUrl);
        try {
            sender.send(message.getRecipient(), campaign.getSubject(), html, String.valueOf(message.getId()));
            message.markSent();
        } catch (Exception e) {
            log.warn("send failed: campaign={} recipient={} reason={}",
                    campaign.getId(), message.getRecipient(), e.getMessage());
            message.markBounced(e.getMessage());
            suppressions.save(Suppression.of(message.getRecipient(), "bounce"));
        }
        messages.save(message);
        completeIfDrained(campaign.getId());
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
