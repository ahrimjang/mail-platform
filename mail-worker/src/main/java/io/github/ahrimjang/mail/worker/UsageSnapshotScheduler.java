package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.core.service.UsageSnapshotService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 월 마감 스냅샷의 박자 담당 — 로직은 전부 {@link UsageSnapshotService}(멱등)에 있다.
 *
 * <p>매일 새벽 한 번이면 충분하다(월 1회 일이지만, 매일 확인해야 월초에 워커가
 * 죽어 있던 날의 공백을 다음날 메꾼다). 부팅 시에도 한 번 돌려서 장기 정지 후
 * 재기동 시나리오까지 캐치업한다. 캡처가 멱등이라 몇 번 겹쳐 불려도 무해.
 */
@Component
public class UsageSnapshotScheduler {

    private final UsageSnapshotService snapshots;

    public UsageSnapshotScheduler(UsageSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @Scheduled(cron = "${APP_USAGE_SNAPSHOT_CRON:0 10 0 * * *}")
    public void captureDaily() {
        snapshots.captureCompletedMonth();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void captureOnBoot() {
        snapshots.captureCompletedMonth();
    }
}
