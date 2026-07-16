package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import io.github.ahrimjang.mail.core.domain.Suppression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Sends one queued message per invocation. Invoked by the worker's queue listener.
 *
 * <p>One call = load the message by id, send it via the {@link MailSender}
 * port, and record the outcome. Campaigns flip to SENDING on first progress
 * and to COMPLETED once their queue is fully drained.
 *
 * <p>Because the queue is at-least-once, redeliveries are expected — and because
 * multiple consumers can race on the same messageId (redelivery landing on a
 * different worker while the first is still mid-send), a status check alone
 * ("skip if not PENDING") is not enough: two callers can both read PENDING
 * before either writes back, and both send. The handler instead opens with
 * {@link MailMessageRepository#claim}, a single atomic conditional update
 * (PENDING -&gt; SENDING) — only one caller can win it. A claim stuck in SENDING
 * past its staleness window is reclaimable too, so a crashed consumer's message
 * is still recovered by the next redelivery instead of getting stuck forever.
 */
@Service
public class MailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MailDispatchService.class);

    /** How long a SENDING claim is honored before it's considered abandoned (crashed consumer) and reclaimable. */
    private static final Duration STALE_CLAIM_AFTER = Duration.ofMinutes(2);

    private final MailMessageRepository messages;
    private final CampaignRepository campaigns;
    private final MailSender sender;
    private final SuppressionRepository suppressions;
    private final TrackingRewriter trackingRewriter;
    private final TemplateRenderer templateRenderer;
    private final ContactRepository contacts;
    private final String baseUrl;

    public MailDispatchService(MailMessageRepository messages,
                               CampaignRepository campaigns,
                               MailSender sender,
                               SuppressionRepository suppressions,
                               TrackingRewriter trackingRewriter,
                               TemplateRenderer templateRenderer,
                               ContactRepository contacts,
                               @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.messages = messages;
        this.campaigns = campaigns;
        this.sender = sender;
        this.suppressions = suppressions;
        this.trackingRewriter = trackingRewriter;
        this.templateRenderer = templateRenderer;
        this.contacts = contacts;
        this.baseUrl = baseUrl;
    }

    /**
     * Process a single queued message by id.
     *
     * <p>Idempotent: a redelivery that loses the {@link MailMessageRepository#claim}
     * race (already claimed/processed elsewhere) is skipped without effect.
     */
    public void dispatchOne(Long messageId) {
        if (!messages.claim(messageId, STALE_CLAIM_AFTER)) {
            log.debug("skip: message {} already claimed/processed by another consumer", messageId);
            return;
        }
        MailMessage message = messages.findById(messageId).orElse(null);
        if (message == null) {
            return;
        }
        Campaign campaign = campaigns.findById(message.getCampaignId()).orElse(null);
        if (campaign == null) {
            message.markFailed("campaign no longer exists");
            messages.save(message);
            return;
        }
        markSending(campaign);
        if (suppressions.existsByWorkspaceAndEmail(campaign.getWorkspaceId(), message.getRecipient())) {
            message.markSuppressed();
            messages.save(message);
            completeIfDrained(campaign.getId());
            return;
        }
        String subject = campaign.getSubject();
        String bodySrc = campaign.getBody();
        // A/B: a held (variant-null) message of a decided campaign renders the winner.
        String variant = message.getVariant();
        if (variant == null && campaign.getAbWinner() != null) {
            variant = campaign.getAbWinner();
        }
        if ("B".equals(variant)) {
            // A/B variant B: swap in the B subject/body where provided (a null B body
            // means a subject-only test — the body stays shared).
            if (campaign.getAbSubjectB() != null) {
                subject = campaign.getAbSubjectB();
            }
            if (campaign.getAbBodyB() != null) {
                bodySrc = campaign.getAbBodyB();
            }
        }
        Map<String, String> vars = Map.of("email", message.getRecipient());
        if (message.getContactId() != null) {
            vars = contacts.findById(message.getContactId()).map(Contact::toVariables).orElse(vars);
        }
        subject = templateRenderer.render(subject, vars);
        bodySrc = templateRenderer.render(bodySrc, vars);
        String trackedBody = trackingRewriter.rewriteLinks(bodySrc, message.getTrackingToken(), baseUrl);
        String html = trackedBody + unsubscribeFooter(message.getUnsubToken())
                + trackingRewriter.openPixel(message.getTrackingToken(), baseUrl);
        try {
            sender.send(message.getRecipient(), subject, html, String.valueOf(message.getId()),
                    campaign.getSenderName(), campaign.getSenderEmail());
            message.markSent();
        } catch (Exception e) {
            // ERROR + throwable so the failure is observable: the stack trace ships to
            // OpenSearch, letting the log dashboard surface it and map it back to source.
            log.error("send failed: campaign={} recipient={}",
                    campaign.getId(), message.getRecipient(), e);
            message.markBounced(e.getMessage());
            suppressions.save(Suppression.of(campaign.getWorkspaceId(), message.getRecipient(), "bounce"));
        }
        messages.save(message);
        completeIfDrained(campaign.getId());
    }

    private String unsubscribeFooter(String token) {
        return "<hr><p style=\"font-size:12px;color:#888\">더 이상 받지 않으려면 "
                + "<a href=\"" + baseUrl + "/api/unsubscribe/" + token + "\">수신거부</a></p>";
    }

    private void markSending(Campaign campaign) {
        // QUEUED -> SENDING only. A list campaign is EXPANDING here — fan-out owns its
        // EXPANDING -> SENDING flip — so this is a no-op for it.
        campaigns.markSendingIfQueued(campaign.getId());
    }

    private void completeIfDrained(Long campaignId) {
        // Cheap EXISTS instead of a full per-status count on every send: a campaign
        // with any PENDING/SENDING left is still draining. completeIfSending only
        // fires from SENDING, so a campaign mid-EXPANDING is never completed early.
        if (!messages.hasPendingOrSending(campaignId)) {
            campaigns.completeIfSending(campaignId);
        }
    }
}
