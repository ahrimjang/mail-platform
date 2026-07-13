package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Releases scheduled campaigns to the send queue once their time arrives.
 *
 * <p>Creation already persisted the campaign and its PENDING messages — only the
 * enqueue step was deferred. This service finds due campaigns, wins an atomic
 * {@link CampaignRepository#claimForEnqueue} (so concurrent schedulers release a
 * campaign exactly once), then publishes one send job per PENDING message. From
 * that point the normal dispatch pipeline takes over unchanged.
 *
 * <p>Invoked periodically by the worker's scheduler.
 */
@Service
public class CampaignScheduleService {

    private static final Logger log = LoggerFactory.getLogger(CampaignScheduleService.class);

    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final MailQueue mailQueue;

    public CampaignScheduleService(CampaignRepository campaigns, MailMessageRepository messages, MailQueue mailQueue) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.mailQueue = mailQueue;
    }

    /**
     * Enqueue every due scheduled campaign. Idempotent: a campaign whose claim
     * is lost (already released elsewhere) is skipped, and even a crash between
     * claim and publish is recoverable by hand (its messages stay PENDING).
     *
     * @return number of campaigns released in this pass
     */
    public int releaseDue() {
        Instant now = Instant.now();
        List<Campaign> due = campaigns.findDueForEnqueue(now);
        int released = 0;
        for (Campaign campaign : due) {
            if (!campaigns.claimForEnqueue(campaign.getId(), now)) {
                continue; // another scheduler won the race
            }
            if (campaign.getListId() != null) {
                // List campaign: recipients were never expanded at create time — hand
                // the fan-out job to the worker now that it is due.
                mailQueue.enqueueFanout(campaign.getId());
                released++;
                log.info("released scheduled list campaign {} (fan-out) scheduledAt={}",
                        campaign.getId(), campaign.getScheduledAt());
            } else {
                List<Long> pendingIds = messages.findPendingIdsByCampaign(campaign.getId());
                pendingIds.forEach(mailQueue::enqueue);
                released++;
                log.info("released scheduled campaign {} ({} messages) scheduledAt={}",
                        campaign.getId(), pendingIds.size(), campaign.getScheduledAt());
            }
        }
        return released;
    }
}
