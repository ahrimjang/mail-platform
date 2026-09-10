package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 복구 스위퍼(AUDIT ARCH-1·2·5). 이 시스템의 동시성은 전부 "원자적 조건부 UPDATE claim"
 * 으로 풀지만, <b>claim 에 이긴 뒤 후속 작업 중 죽는</b> 경우의 복구 경로가 비어 있었다.
 * 재전달된 잡은 claim 에서 져 정상 return 하므로 DLQ 도 가지 않는다 — 조용한 영구 고착이다.
 *
 * <p>세 가지를 걷는다. 판정 기준은 전부 "{@value #STUCK_MINUTES}분 넘게 그 상태" —
 * 정상 처리에 그만큼 걸리는 일은 없고, 오탐이어도 재발행은 claim 이 멱등하게 받아낸다.
 * <ol>
 *   <li><b>EXPANDING 고착</b>: 팬아웃 도중 사망. QUEUED 로 조건부 되돌리고 팬아웃을
 *       재발행한다. 팬아웃은 이미 만든 메시지 뒤부터 재개한다.</li>
 *   <li><b>고아 QUEUED</b>: 릴리스는 됐는데 잡이 없다. 리스트 캠페인은 팬아웃 재발행,
 *       메시지가 하나도 없는 애드혹은 create() 실패의 잔존물이라 CANCELED 로 닫는다.</li>
 *   <li><b>고아·stale 메시지</b>: 릴리스된 캠페인에서 오래도록 PENDING/SENDING 인 행 —
 *       발행 직후 크래시, 발송 중 사망, 승자 확정 뒤 릴리스 실패(홀드아웃)가 전부 여기 걸린다.
 *       재발행하고 updatedAt 을 갱신해 다음 스위프에서 또 잡히지 않게 한다.</li>
 * </ol>
 *
 * <p>비용 상한: 3번은 한 번에 {@value #STALE_BATCH}건까지. 속도 제한에 오래 파킹된 PENDING
 * 도 여기 걸릴 수 있는데(구분할 방법이 없다), 중복 잡은 throttle 경로로 다시 파킹될 뿐
 * 발송 결과에는 영향이 없고, 배치 상한과 updatedAt 갱신이 증폭을 막는다.
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    static final int STUCK_MINUTES = 10;
    static final int STALE_BATCH = 200;

    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final MailQueue mailQueue;

    public RecoveryService(CampaignRepository campaigns, MailMessageRepository messages, MailQueue mailQueue) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.mailQueue = mailQueue;
    }

    /** 한 번의 스위프 결과 — 로그·지표용. */
    public record Sweep(int expandingReset, int orphanFanoutRepublished, int orphanCanceled, int staleRepublished) {
        public int total() {
            return expandingReset + orphanFanoutRepublished + orphanCanceled + staleRepublished;
        }
    }

    public Sweep sweep() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofMinutes(STUCK_MINUTES));
        int expandingReset = 0;
        int orphanFanout = 0;
        int orphanCanceled = 0;

        // 1) 팬아웃 도중 죽은 캠페인 — 되돌리기에 이긴 호출만 재발행한다
        for (Campaign c : campaigns.findStuckExpanding(cutoff)) {
            if (campaigns.resetExpandingToQueued(c.getId(), cutoff)) {
                mailQueue.enqueueFanout(c.getId());
                expandingReset++;
                log.warn("복구: EXPANDING 고착 캠페인 {} 을 QUEUED 로 되돌리고 팬아웃 재발행", c.getId());
            }
        }

        // 2) 릴리스됐는데 잡이 없는 QUEUED
        for (Campaign c : campaigns.findOrphanQueued(cutoff)) {
            if (c.getListId() != null) {
                // 팬아웃 재발행 — 이미 진행 중이었다면 QUEUED→EXPANDING claim 에서 져 무해하다
                mailQueue.enqueueFanout(c.getId());
                orphanFanout++;
                log.warn("복구: 고아 QUEUED 리스트 캠페인 {} 팬아웃 재발행", c.getId());
            } else if (messages.findPendingIdsByCampaign(c.getId()).isEmpty()
                    && !messages.hasPendingOrSending(c.getId())) {
                // 메시지가 하나도 없다 — create() 가 저장 뒤 검증에서 실패한 잔존물. 보낼 게 없다.
                campaigns.updateStatus(c.getId(), CampaignStatus.CANCELED);
                orphanCanceled++;
                log.warn("복구: 메시지 없는 고아 QUEUED 캠페인 {} 을 CANCELED 로 닫음", c.getId());
            }
            // 메시지가 있는 애드혹 고아는 3) 이 메시지 단위로 재발행한다
        }

        // 3) 오래도록 PENDING/SENDING 인 메시지 재발행
        List<Long> stale = messages.findStaleIds(cutoff, STALE_BATCH);
        if (!stale.isEmpty()) {
            messages.touchPending(stale, now);
            stale.forEach(mailQueue::enqueue);
            log.warn("복구: 고착 메시지 {}건 재발행 (cutoff={})", stale.size(), cutoff);
        }

        Sweep result = new Sweep(expandingReset, orphanFanout, orphanCanceled, stale.size());
        if (result.total() > 0) {
            log.warn("복구 스위프 결과: {}", result);
        }
        return result;
    }
}
