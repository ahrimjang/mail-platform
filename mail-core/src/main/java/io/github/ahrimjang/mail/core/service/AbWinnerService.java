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

import java.time.Duration;
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
 *
 * <p>판정에 <b>근거가 부족하면 미룬다</b>(ARCH-6). 예전엔 표본 0·동률·반응 0 이 전부
 * 조용히 "A 승"으로 확정됐고, 테스트 배치가 다 나가기도 전에(속도 제한) 판정이 돌았다.
 * 이제 테스트 배치가 다 끝나고, 변형별 발송이 최소 표본 이상이며, 반응이 하나라도 있고,
 * 동률이 아닐 때 확정한다. 다만 영원히 미루면 홀드아웃(최대 95%)이 영영 안 나가므로
 * 릴리스 후 {@value #MAX_DEFER_HOURS}시간이 지나면 있는 근거로 확정한다(없으면 A).
 */
@Service
public class AbWinnerService {

    private static final Logger log = LoggerFactory.getLogger(AbWinnerService.class);

    /** 변형별 최소 발송 수 — 이보다 적으면 비율이 우연에 좌우된다. */
    static final int MIN_SENT_PER_VARIANT = 10;
    /** 근거 부족 시 다음 평가까지 미루는 간격. */
    static final Duration DEFER = Duration.ofMinutes(10);
    /** 릴리스 후 이 시간이 지나면 근거가 부족해도 확정한다 — 홀드아웃을 영영 붙들 수는 없다. */
    static final int MAX_DEFER_HOURS = 24;

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
            Verdict verdict = judge(campaign);
            if (!verdict.conclusive() && !pastDeadline(campaign, now)) {
                campaigns.scheduleAbEvaluation(campaign.getId(), now.plus(DEFER));
                log.info("A/B evaluation of campaign {} deferred {}min: {}",
                        campaign.getId(), DEFER.toMinutes(), verdict.reason());
                continue;
            }
            String winner = verdict.winner();
            if (!campaigns.claimAbWinner(campaign.getId(), winner)) {
                continue; // another scheduler decided it first
            }
            if (!verdict.conclusive()) {
                log.warn("A/B winner for campaign {} decided on weak evidence after {}h: {} -> {}",
                        campaign.getId(), MAX_DEFER_HOURS, verdict.reason(), winner);
            }
            List<Long> heldIds = messages.findPendingHeldIdsByCampaign(campaign.getId());
            heldIds.forEach(mailQueue::enqueue);
            decided++;
            log.info("A/B winner for campaign {}: variant {} ({} held messages released)",
                    campaign.getId(), winner, heldIds.size());
        }
        return decided;
    }

    /**
     * @param winner     확정할 안 (근거가 없으면 "A" — 대기 한도를 넘겼을 때만 쓰인다)
     * @param conclusive 근거가 충분한가
     * @param reason     부족하면 왜
     */
    record Verdict(String winner, boolean conclusive, String reason) {
        static Verdict decided(String winner) {
            return new Verdict(winner, true, null);
        }
        static Verdict inconclusive(String fallback, String reason) {
            return new Verdict(fallback, false, reason);
        }
    }

    /** 판정 재료를 모아 결론 또는 "아직 이르다"를 낸다. */
    Verdict judge(Campaign campaign) {
        if (messages.hasUnfinishedTestBatch(campaign.getId())) {
            return Verdict.inconclusive("A", "테스트 배치 발송 미완료");
        }
        EventType type = "CLICK".equals(campaign.getAbEvalMetric()) ? EventType.CLICK : EventType.OPEN;
        long sentA = 0, sentB = 0, engagedA = 0, engagedB = 0;
        for (VariantDelivery d : messages.countByCampaignAndVariant(campaign.getId())) {
            long engaged = events.countDistinctMessagesByVariant(campaign.getId(), type, d.variant());
            if ("A".equals(d.variant())) { sentA = d.sent(); engagedA = engaged; }
            if ("B".equals(d.variant())) { sentB = d.sent(); engagedB = engaged; }
        }
        double rateA = sentA == 0 ? 0 : (double) engagedA / sentA;
        double rateB = sentB == 0 ? 0 : (double) engagedB / sentB;
        // 근거가 있을 땐 높은 쪽, 없을 땐 A — 어느 경우든 "확정해야 한다면" 이 값
        String leader = rateB > rateA ? "B" : "A";

        if (sentA < MIN_SENT_PER_VARIANT || sentB < MIN_SENT_PER_VARIANT) {
            return Verdict.inconclusive(leader, String.format(
                    "표본 부족 (A %d통, B %d통 — 변형별 최소 %d통)", sentA, sentB, MIN_SENT_PER_VARIANT));
        }
        if (engagedA + engagedB == 0) {
            return Verdict.inconclusive(leader, "반응 없음 (" + type + " 0건)");
        }
        if (rateA == rateB) {
            return Verdict.inconclusive(leader, String.format("동률 (%.1f%%)", rateA * 100));
        }
        return Verdict.decided(leader);
    }

    /** 릴리스(enqueuedAt) 후 대기 + 최대 유예를 넘겼는가. 릴리스 시각을 모르면 더 미루지 않는다. */
    private static boolean pastDeadline(Campaign campaign, Instant now) {
        Instant released = campaign.getEnqueuedAt();
        if (released == null) {
            return true;
        }
        int waitMinutes = campaign.getAbEvalWaitMinutes() == null ? 60 : campaign.getAbEvalWaitMinutes();
        return now.isAfter(released.plus(Duration.ofMinutes(waitMinutes)).plus(Duration.ofHours(MAX_DEFER_HOURS)));
    }
}
