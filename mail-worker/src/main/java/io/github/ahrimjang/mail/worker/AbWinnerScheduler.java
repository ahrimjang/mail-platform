package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.core.service.AbWinnerService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for A/B winner decisions: every poll asks the core service to
 * evaluate whatever is due. All the actual logic (due query, rate comparison,
 * atomic winner claim, held-message release) lives in {@link AbWinnerService};
 * this class only owns the cadence.
 *
 * <p>Safe with multiple worker instances — the claim is a conditional UPDATE, so
 * concurrent pollers decide each campaign exactly once.
 */
@Component
public class AbWinnerScheduler {

    private final AbWinnerService winnerService;

    public AbWinnerScheduler(AbWinnerService winnerService) {
        this.winnerService = winnerService;
    }

    /** 30s cadence: winner evaluation tolerates minutes-level wait times comfortably. */
    @Scheduled(fixedDelay = 30_000)
    public void evaluateDueCampaigns() {
        winnerService.evaluateDue();
    }
}
