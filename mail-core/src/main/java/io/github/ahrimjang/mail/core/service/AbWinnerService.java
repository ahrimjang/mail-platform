package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.VariantDelivery;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Decides the winning variant of due A/B winner-flow campaigns and releases the
 * held-out remainder with it. Invoked periodically by the worker's scheduler.
 *
 * <p>The winner is claimed with a single conditional UPDATE (ab_winner IS NULL),
 * so concurrent schedulers decide each campaign exactly once. Held rows are never
 * rewritten: they stay variant-null and dispatch renders the campaign's decided
 * winner for them, which also keeps the A/B comparison stats test-group-only.
 */
@Service
public class AbWinnerService {

    private static final Logger log = LoggerFactory.getLogger(AbWinnerService.class);

    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final EmailEventRepository events;
    private final MailQueue mailQueue;

    public AbWinnerService(CampaignRepository campaigns, MailMessageRepository messages,
                           EmailEventRepository events, MailQueue mailQueue) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.events = events;
        this.mailQueue = mailQueue;
    }

    /** Evaluate every due campaign. @return number of campaigns decided in this pass. */
    public int evaluateDue() {
        Instant now = Instant.now();
        int decided = 0;
        for (Campaign campaign : campaigns.findDueForAbEvaluation(now)) {
            String winner = pickWinner(campaign);
            if (!campaigns.claimAbWinner(campaign.getId(), winner)) {
                continue; // another scheduler decided it first
            }
            List<Long> heldIds = messages.findPendingHeldIdsByCampaign(campaign.getId());
            heldIds.forEach(mailQueue::enqueue);
            decided++;
            log.info("A/B winner for campaign {}: variant {} ({} held messages released)",
                    campaign.getId(), winner, heldIds.size());
        }
        return decided;
    }

    /** Higher engagement rate wins; ties (and zero engagement) fall back to A. */
    private String pickWinner(Campaign campaign) {
        EventType type = "CLICK".equals(campaign.getAbEvalMetric()) ? EventType.CLICK : EventType.OPEN;
        double rateA = 0, rateB = 0;
        for (VariantDelivery d : messages.countByCampaignAndVariant(campaign.getId())) {
            long engaged = events.countDistinctMessagesByVariant(campaign.getId(), type, d.variant());
            double rate = d.sent() == 0 ? 0 : (double) engaged / d.sent();
            if ("A".equals(d.variant())) rateA = rate;
            if ("B".equals(d.variant())) rateB = rate;
        }
        return rateB > rateA ? "B" : "A";
    }
}
