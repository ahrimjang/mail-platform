package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.core.service.CampaignScheduleService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for scheduled campaigns: every poll asks the core service to
 * release whatever is due. All the actual logic (due query, atomic claim, enqueue)
 * lives in {@link CampaignScheduleService}; this class only owns the cadence.
 *
 * <p>Safe with multiple worker instances — the claim is a conditional UPDATE, so
 * concurrent pollers release each campaign exactly once.
 */
@Component
public class ScheduledCampaignReleaser {

    private final CampaignScheduleService scheduleService;

    public ScheduledCampaignReleaser(CampaignScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 10s cadence: fine-grained enough for a send scheduler, cheap on the DB (indexed partial scan). */
    @Scheduled(fixedDelay = 10_000)
    public void releaseDueCampaigns() {
        scheduleService.releaseDue();
    }
}
