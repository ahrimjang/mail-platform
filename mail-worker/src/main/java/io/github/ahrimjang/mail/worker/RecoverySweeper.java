package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.core.service.RecoveryService;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 복구 스위퍼의 주기 트리거. 판정·되돌리기·재발행은 전부 {@link RecoveryService} 에 있고,
 * 여기는 주기와 지표만 담당한다(릴리서·승자 스케줄러와 같은 분업).
 *
 * <p>여러 워커가 떠 있어도 안전하다 — 되돌리기는 조건부 UPDATE 이고 재발행은 소비 쪽 claim
 * 이 멱등하게 받는다. 스위프 자체가 죽어도 다음 주기에 다시 돈다.
 */
@Component
public class RecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);

    private final RecoveryService recovery;

    public RecoverySweeper(RecoveryService recovery) {
        this.recovery = recovery;
    }

    /** 60초 주기, 기동 60초 뒤 시작 — 고착 판정 기준(10분)에 비해 충분히 촘촘하고 DB 부담은 작다. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() {
        try {
            RecoveryService.Sweep result = recovery.sweep();
            // 0 이 아닌 스위프가 반복되면 무언가 계속 죽고 있다는 뜻 — Grafana 에서 본다
            count("expanding_reset", result.expandingReset());
            count("orphan_fanout", result.orphanFanoutRepublished());
            count("orphan_canceled", result.orphanCanceled());
            count("stale_message", result.staleRepublished());
        } catch (Exception e) {
            // 스위퍼가 죽어서 발송 파이프라인에 영향을 주면 안 된다 — 다음 주기에 재시도
            log.error("복구 스위프 실패", e);
        }
    }

    private static void count(String kind, int n) {
        if (n > 0) {
            Metrics.counter("mail.recovery", "kind", kind).increment(n);
        }
    }
}
