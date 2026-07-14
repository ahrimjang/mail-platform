package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Expands a list campaign's recipients into the send queue, asynchronously and in
 * batches. Invoked by the worker's fan-out listener, one call per campaign.
 *
 * <p>This is the deferred half of campaign creation: {@code CampaignService.create()}
 * only persists the campaign and publishes a fan-out job, so the API returns in O(1);
 * the heavy N-row expansion happens here off the request path.
 *
 * <p>Idempotent via an atomic QUEUED-&gt;EXPANDING claim: RabbitMQ is at-least-once, so a
 * redelivered fan-out job loses the claim and is skipped instead of creating duplicate
 * messages. The campaign stays EXPANDING for the whole expansion so dispatch never
 * completes it early (its later messages aren't created yet); fan-out flips it to
 * SENDING when done and completes it if everything already drained.
 */
@Service
public class CampaignFanoutService {

    private static final Logger log = LoggerFactory.getLogger(CampaignFanoutService.class);

    /** Recipients expanded per DB round-trip. Keeps memory bounded on million-row lists. */
    private static final int PAGE = 1000;

    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final ContactRepository contacts;
    private final MailQueue mailQueue;

    public CampaignFanoutService(CampaignRepository campaigns, MailMessageRepository messages,
                                 ContactRepository contacts, MailQueue mailQueue) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.contacts = contacts;
        this.mailQueue = mailQueue;
    }

    /**
     * Expand one list campaign. Safe to call more than once (redelivery): only the
     * caller that wins the QUEUED-&gt;EXPANDING claim does the work.
     */
    public void expand(Long campaignId) {
        if (!campaigns.claimForFanout(campaignId)) {
            log.debug("skip fan-out: campaign {} already claimed/expanded by another consumer", campaignId);
            return;
        }
        Campaign campaign = campaigns.findById(campaignId).orElse(null);
        if (campaign == null || campaign.getListId() == null) {
            return;
        }
        Long listId = campaign.getListId();

        long afterId = 0L;
        long total = 0;
        while (true) {
            List<Contact> page = contacts.findSubscribedByListIdAfter(listId, afterId, PAGE);
            if (page.isEmpty()) {
                break;
            }
            List<MailMessage> batch = page.stream()
                    .map(c -> {
                        MailMessage m = MailMessage.queued(campaignId, c.getEmail(), c.getId());
                        if (campaign.isAbTest()) {
                            m.setVariant(campaign.hasWinnerFlow()
                                    ? AbVariantAssigner.assignWithHoldout(c.getEmail(),
                                            campaign.getAbTestPercent(), campaign.getAbSplitPercent())
                                    : AbVariantAssigner.assign(c.getEmail(), campaign.getAbSplitPercent()));
                        }
                        return m;
                    })
                    .toList();
            List<MailMessage> saved = messages.saveAll(batch);
            // Winner flow only enqueues the test batch: held rows (variant null)
            // stay PENDING until the winner is decided.
            saved.stream()
                    .filter(m -> !campaign.hasWinnerFlow() || m.getVariant() != null)
                    .forEach(m -> mailQueue.enqueue(m.getId()));
            total += saved.size();
            afterId = page.get(page.size() - 1).getId();
            if (page.size() < PAGE) {
                break;
            }
        }

        campaigns.markExpanded(campaignId); // EXPANDING -> SENDING
        // Winner flow: the test batch just went out — stamp when the winner scheduler
        // should evaluate it and release the held-out remainder.
        if (campaign.hasWinnerFlow()) {
            campaigns.scheduleAbEvaluation(campaignId,
                    Instant.now().plus(Duration.ofMinutes(campaign.getAbEvalWaitMinutes())));
        }
        // If every message already drained before we flipped to SENDING (fast sends /
        // empty list), finish it here — cheap EXISTS, not a full count.
        if (!messages.hasPendingOrSending(campaignId)) {
            campaigns.completeIfSending(campaignId);
        }
        log.info("fanned out campaign {} into {} messages", campaignId, total);
    }
}
