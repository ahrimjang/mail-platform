package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.UsageSnapshotView;
import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.domain.UsageSnapshot;
import io.github.ahrimjang.mail.core.port.UsageSnapshotRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageSnapshotServiceTest {

    private static final long WS = 7L;

    @Mock
    private UsageSnapshotRepository snapshots;
    @Mock
    private WorkspaceContext ctx;

    private UsageSnapshotService service;

    @BeforeEach
    void setUp() {
        // 2026-07 중순으로 고정 — "가장 최근에 끝난 달"은 2026-06 이어야 한다
        Clock fixed = Clock.fixed(Instant.parse("2026-07-15T09:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new UsageSnapshotService(snapshots, ctx, fixed);
        lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
        lenient().when(ctx.isAdmin()).thenReturn(true);
    }

    @Test
    void capture_targetsThePreviousMonth() {
        when(snapshots.captureMonth(YearMonth.of(2026, 6))).thenReturn(3);

        assertThat(service.captureCompletedMonth()).isEqualTo(3);
        verify(snapshots).captureMonth(YearMonth.of(2026, 6));
    }

    @Test
    void history_mapsThePlanPriceAsTheMonthlyCharge() {
        when(snapshots.findByWorkspace(WS)).thenReturn(List.of(
                new UsageSnapshot(WS, YearMonth.of(2026, 6), 12_480, Plan.STANDARD,
                        Instant.parse("2026-07-01T00:10:00Z")),
                new UsageSnapshot(WS, YearMonth.of(2026, 5), 800, Plan.STARTER,
                        Instant.parse("2026-06-01T00:10:00Z"))));

        List<UsageSnapshotView> history = service.history();

        assertThat(history).hasSize(2);
        assertThat(history.get(0).periodMonth()).isEqualTo("2026-06");
        assertThat(history.get(0).amountKrw()).isEqualTo(9_900);   // 스탠다드 월정액이 곧 청구액
        assertThat(history.get(1).amountKrw()).isEqualTo(0);       // 스타터는 무료
    }

    @Test
    void history_requiresTheAdminRole() {
        when(ctx.isAdmin()).thenReturn(false);

        assertThatThrownBy(() -> service.history()).isInstanceOf(ForbiddenException.class);
    }
}
