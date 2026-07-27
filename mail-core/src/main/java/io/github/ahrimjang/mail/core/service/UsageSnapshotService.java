package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.UsageSnapshotView;
import io.github.ahrimjang.mail.core.port.UsageSnapshotRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

/**
 * 월 마감 스냅샷 유스케이스 — 캡처(워커 스케줄러가 호출)와 청구 이력 조회(콘솔).
 *
 * <p>캡처는 "지난달(가장 최근에 끝난 달)"을 대상으로 하고 멱등이다: 스케줄러가
 * 몇 번을 다시 불러도, 워커가 월초에 죽어 있다가 며칠 뒤에 떠도, 이미 캡처된
 * 달은 건드리지 않고 빠진 달만 채운다(부팅 캐치업이 이 성질에 기댄다).
 */
@Service
public class UsageSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(UsageSnapshotService.class);

    private final UsageSnapshotRepository snapshots;
    private final WorkspaceContext ctx;
    private final Clock clock;

    // 생성자가 둘(운영용/테스트용 Clock 주입)이라 Spring 이 고를 쪽을 명시해야 한다
    @org.springframework.beans.factory.annotation.Autowired
    public UsageSnapshotService(UsageSnapshotRepository snapshots, WorkspaceContext ctx) {
        this(snapshots, ctx, Clock.systemDefaultZone());
    }

    UsageSnapshotService(UsageSnapshotRepository snapshots, WorkspaceContext ctx, Clock clock) {
        this.snapshots = snapshots;
        this.ctx = ctx;
        this.clock = clock;
    }

    /** 지난달 사용량을 전 워크스페이스에 대해 고정한다. 새로 캡처된 행 수를 돌려준다. */
    public int captureCompletedMonth() {
        YearMonth previous = YearMonth.now(clock).minusMonths(1);
        int captured = snapshots.captureMonth(previous);
        if (captured > 0) {
            log.info("월 마감 스냅샷 캡처: {} — {}개 워크스페이스", previous, captured);
        }
        return captured;
    }

    /** 내 워크스페이스의 청구 이력 (ADMIN — 요금이 실리는 화면이므로). */
    public List<UsageSnapshotView> history() {
        if (!ctx.isAdmin()) {
            throw new ForbiddenException("workspace admin role required");
        }
        return snapshots.findByWorkspace(ctx.currentWorkspaceId()).stream()
                .map(s -> new UsageSnapshotView(
                        s.period().toString(),          // "2026-06"
                        s.sentCount(),
                        s.plan().name(),
                        s.plan().monthlyPriceKrw(),     // 구간 월정액 = 그 달 청구액
                        s.capturedAt()))
                .toList();
    }
}
