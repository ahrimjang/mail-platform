package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 워커 기동 시 최근 이벤트의 "최초 참여" 기록 빈틈을 메운다(V36).
 *
 * <p>배포 중에는 새 api 가 마이그레이션을 끝낸 뒤에도 옛 워커가 잠깐 더 돌며, 카운터를 모르는 옛 코드로
 * 이벤트만 쌓는다. 그 틈의 오픈·클릭이 캠페인 숫자에서 빠지지 않게 여기서 채운다. 넣은 것만 세는
 * 조건부 문장이라 몇 번을 돌려도 두 번 세지 않는다.
 */
@Component
public class EngagementReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EngagementReconciler.class);

    /** 배포 한 번의 틈을 넉넉히 덮는 창 — occurred_at 인덱스(V36)로 이 구간만 읽는다. */
    static final Duration WINDOW = Duration.ofHours(24);

    private final EmailEventRepository events;

    public EngagementReconciler(EmailEventRepository events) {
        this.events = events;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int fixed = events.reconcileEngagementSince(Instant.now().minus(WINDOW));
            if (fixed > 0) {
                log.warn("참여 카운터 재조정: 캠페인·안 {}곳 보정 (최근 {}시간 이벤트 기준)", fixed, WINDOW.toHours());
            }
        } catch (Exception e) {
            // 기동을 막을 일은 아니다 — 숫자가 조금 모자랄 뿐 발송에는 영향이 없다
            log.error("참여 카운터 재조정 실패", e);
        }
    }
}
