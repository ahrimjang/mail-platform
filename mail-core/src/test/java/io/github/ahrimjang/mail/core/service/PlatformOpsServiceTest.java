package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.common.OpsCampaignRow;
import io.github.ahrimjang.mail.common.OpsSignalsView;
import io.github.ahrimjang.mail.common.OpsWorkspaceRow;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.domain.PlatformAuditEntry;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.WorkspaceStatusCount;
import io.github.ahrimjang.mail.core.port.PlatformAuditRepository;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 플랫폼 운영자 서비스 — 세 가지를 잠근다: 권한 없는 호출은 아무것도 건드리지 않는다,
 * 모든 조치는 감사 로그와 테넌트 알림을 남긴다, 조치는 기존 도메인 규칙(정지 컬럼·플랜
 * 클램프·abort 조건부 UPDATE)을 그대로 탄다.
 */
@ExtendWith(MockitoExtension.class)
class PlatformOpsServiceTest {

    private static final long WS = 7L;

    @Mock WorkspaceContext ctx;
    @Mock WorkspaceRepository workspaces;
    @Mock UserRepository users;
    @Mock CampaignRepository campaigns;
    @Mock MailMessageRepository messages;
    @Mock PlatformAuditRepository audit;
    @Mock NotificationService notifications;

    @InjectMocks PlatformOpsService service;

    private Workspace ws;

    @BeforeEach
    void setUp() {
        lenient().when(ctx.isPlatformOperator()).thenReturn(true);
        lenient().when(ctx.currentUserEmail()).thenReturn("ops@outpace.test");
        ws = Workspace.of("고객사");
        ws.setId(WS);
        lenient().when(workspaces.findById(WS)).thenReturn(Optional.of(ws));
        lenient().when(workspaces.findAll()).thenReturn(List.of(ws));
        lenient().when(workspaces.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(messages.aggregateByWorkspaceSince(any())).thenReturn(List.of());
        lenient().when(users.findByRole(any())).thenReturn(List.of());
    }

    // ── 권한 ─────────────────────────────────────────────────────────────

    @Test
    void 운영자가_아니면_403이고_아무것도_건드리지_않는다() {
        when(ctx.isPlatformOperator()).thenReturn(false);

        assertThatThrownBy(() -> service.workspaces()).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.suspend(WS, "스팸")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.abortCampaign(1L, "오발송")).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(workspaces, campaigns, audit, notifications);
    }

    // ── 정지 / 해제 ──────────────────────────────────────────────────────

    @Test
    void 정지는_사유_필수이고_컬럼_감사_알림을_한_번에_남긴다() {
        assertThatThrownBy(() -> service.suspend(WS, " ")).isInstanceOf(IllegalArgumentException.class);
        verify(workspaces, never()).save(any());

        OpsWorkspaceRow row = service.suspend(WS, "스팸 신고 3건");

        assertThat(ws.isSendingSuspended()).isTrue();
        assertThat(ws.getSuspensionReason()).isEqualTo("운영자 정지: 스팸 신고 3건");
        assertThat(row.suspendedAt()).isNotNull();
        ArgumentCaptor<PlatformAuditEntry> saved = ArgumentCaptor.forClass(PlatformAuditEntry.class);
        verify(audit).save(saved.capture());
        assertThat(saved.getValue().getAction()).isEqualTo(PlatformAuditEntry.SUSPEND);
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(WS);
        assertThat(saved.getValue().getActorEmail()).isEqualTo("ops@outpace.test");
        verify(notifications).sendingSuspended(WS, "스팸 신고 3건");
    }

    @Test
    void 이미_정지된_워크스페이스는_다시_정지할_수_없다() {
        ws.setSendingSuspendedAt(Instant.now());
        ws.setSuspensionReason("자동 정지");

        assertThatThrownBy(() -> service.suspend(WS, "또")).isInstanceOf(IllegalStateException.class);
        verify(audit, never()).save(any());
    }

    @Test
    void 해제는_자동정지도_풀고_이전_사유를_감사에_남긴다() {
        ws.setSendingSuspendedAt(Instant.now());
        ws.setSuspensionReason("최근 7일 바운스율 12.0% — 자동 정지");

        OpsWorkspaceRow row = service.unsuspend(WS, "명단 교체 확인");

        assertThat(ws.isSendingSuspended()).isFalse();
        assertThat(ws.getSuspensionReason()).isNull();
        assertThat(row.suspendedAt()).isNull();
        ArgumentCaptor<PlatformAuditEntry> saved = ArgumentCaptor.forClass(PlatformAuditEntry.class);
        verify(audit).save(saved.capture());
        assertThat(saved.getValue().getAction()).isEqualTo(PlatformAuditEntry.UNSUSPEND);
        assertThat(saved.getValue().getDetail()).contains("명단 교체 확인").contains("자동 정지");
        verify(notifications).sendingResumed(WS);
    }

    @Test
    void 정지_상태가_아니면_해제할_수_없다() {
        assertThatThrownBy(() -> service.unsuspend(WS, "x")).isInstanceOf(IllegalStateException.class);
    }

    // ── 플랜 ─────────────────────────────────────────────────────────────

    @Test
    void 플랜_변경은_결제_없이_적용되고_속도_설정을_새_상한으로_클램프한다() {
        ws.setPlan(Plan.PRO);
        ws.setSendRatePerSec(40);   // PRO 상한 50 이내

        OpsWorkspaceRow row = service.changePlan(WS, "standard", "체험 종료");

        assertThat(ws.getPlan()).isEqualTo(Plan.STANDARD);
        assertThat(ws.getSendRatePerSec()).isEqualTo(Plan.STANDARD.sendRateCap());   // 40 → 20
        assertThat(row.plan()).isEqualTo("STANDARD");
        ArgumentCaptor<PlatformAuditEntry> saved = ArgumentCaptor.forClass(PlatformAuditEntry.class);
        verify(audit).save(saved.capture());
        assertThat(saved.getValue().getDetail()).startsWith("PRO → STANDARD");
        verify(notifications).planChanged(WS, "STANDARD");
    }

    @Test
    void 엔터프라이즈도_운영자는_줄_수_있고_속도_상한이_없으면_설정을_건드리지_않는다() {
        ws.setSendRatePerSec(5);

        service.changePlan(WS, "ENTERPRISE", "계약 체결");

        assertThat(ws.getPlan()).isEqualTo(Plan.ENTERPRISE);
        assertThat(ws.getSendRatePerSec()).isEqualTo(5);
    }

    @Test
    void 같은_플랜이나_모르는_플랜은_거절한다() {
        assertThatThrownBy(() -> service.changePlan(WS, "STARTER", "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.changePlan(WS, "GOLD", "x")).isInstanceOf(IllegalArgumentException.class);
        verify(workspaces, never()).save(any());
    }

    // ── API 키 ───────────────────────────────────────────────────────────

    @Test
    void 키_폐기는_발급된_키가_있을_때만() {
        assertThatThrownBy(() -> service.revokeApiKey(WS, "유출")).isInstanceOf(IllegalStateException.class);

        ws.setApiKey("opk_live_abc");
        OpsWorkspaceRow row = service.revokeApiKey(WS, "유출 신고");

        assertThat(ws.getApiKey()).isNull();
        assertThat(row.apiKeyIssued()).isFalse();
        verify(audit).save(any());
    }

    // ── 캠페인 중단 ──────────────────────────────────────────────────────

    @Test
    void 캠페인_중단은_콘솔과_같은_조건부_UPDATE를_타고_남은_건수를_감사에_남긴다() {
        Campaign c = campaign(42L, CampaignStatus.SENDING);
        when(campaigns.findById(42L)).thenReturn(Optional.of(c));
        when(campaigns.abort(42L)).thenReturn(true);
        when(messages.cancelPendingByCampaign(42L)).thenReturn(120);
        when(messages.countByCampaign(42L)).thenReturn(new MessageCounts(200, 0, 0, 80, 0, 0, 0));

        OpsCampaignRow row = service.abortCampaign(42L, "오발송 신고");

        assertThat(row.id()).isEqualTo(42L);
        assertThat(row.workspaceName()).isEqualTo("고객사");
        ArgumentCaptor<PlatformAuditEntry> saved = ArgumentCaptor.forClass(PlatformAuditEntry.class);
        verify(audit).save(saved.capture());
        assertThat(saved.getValue().getAction()).isEqualTo(PlatformAuditEntry.ABORT_CAMPAIGN);
        assertThat(saved.getValue().getCampaignId()).isEqualTo(42L);
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(WS);
        assertThat(saved.getValue().getDetail()).contains("120건");
    }

    @Test
    void 이미_끝난_캠페인은_중단_실패이고_메시지를_건드리지_않는다() {
        when(campaigns.findById(42L)).thenReturn(Optional.of(campaign(42L, CampaignStatus.COMPLETED)));
        when(campaigns.abort(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.abortCampaign(42L, "x")).isInstanceOf(IllegalStateException.class);
        verify(messages, never()).cancelPendingByCampaign(anyLong());
        verify(audit, never()).save(any());
    }

    @Test
    void 없는_캠페인은_404다() {
        when(campaigns.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.abortCampaign(99L, "x")).isInstanceOf(NoSuchElementException.class);
    }

    // ── 목록 집계 ────────────────────────────────────────────────────────

    @Test
    void 목록은_그룹_집계에서_월_발송_30일_바운스_소유자를_뽑고_바운스율_높은_순이다() {
        Workspace clean = Workspace.of("깨끗");
        clean.setId(8L);
        when(workspaces.findAll()).thenReturn(List.of(clean, ws));
        Instant t = Instant.now();
        // 첫 호출(이번 달)·둘째 호출(30일 창) 모두 같은 데이터를 돌려줘도 무방 — 계산 경로만 본다
        when(messages.aggregateByWorkspaceSince(any())).thenReturn(List.of(
                new WorkspaceStatusCount(WS, MessageStatus.SENT, 90, t),
                new WorkspaceStatusCount(WS, MessageStatus.BOUNCED, 10, t),
                new WorkspaceStatusCount(WS, MessageStatus.FAILED, 3, t),
                new WorkspaceStatusCount(8L, MessageStatus.SENT, 50, t)));
        User owner = User.register("owner@ws.com", "h", "대표");
        owner.setWorkspaceId(WS);
        owner.setRole("ADMIN");
        owner.setEmailVerifiedAt(t);
        User op = User.register("op@ws.com", "h", null);
        op.setWorkspaceId(WS);
        op.setRole("OPERATOR");
        when(users.findByRole("ADMIN")).thenReturn(List.of(owner));
        when(users.findByRole("OPERATOR")).thenReturn(List.of(op));

        List<OpsWorkspaceRow> rows = service.workspaces();

        assertThat(rows).extracting(OpsWorkspaceRow::id).containsExactly(WS, 8L);   // 10% 가 0% 보다 위
        OpsWorkspaceRow r = rows.get(0);
        assertThat(r.monthlySent()).isEqualTo(90);
        assertThat(r.attempted30d()).isEqualTo(100);   // SENT+BOUNCED, FAILED 제외
        assertThat(r.bounced30d()).isEqualTo(10);
        assertThat(r.bounceRate30d()).isEqualTo(0.10);
        assertThat(r.ownerEmail()).isEqualTo("owner@ws.com");
        assertThat(r.ownerVerified()).isTrue();
        assertThat(r.memberCount()).isEqualTo(2);
        assertThat(rows.get(1).ownerEmail()).isNull();
        assertThat(rows.get(1).memberCount()).isZero();
    }

    @Test
    void 신호는_정지_목록과_진행_중_캠페인과_24시간_카운터를_모은다() {
        ws.setSendingSuspendedAt(Instant.now());
        Campaign inflight = campaign(5L, CampaignStatus.SENDING);
        when(campaigns.findInFlight()).thenReturn(List.of(inflight));
        when(messages.countByCampaign(5L)).thenReturn(new MessageCounts(10, 4, 1, 5, 0, 0, 0));
        when(workspaces.count()).thenReturn(3L);
        when(workspaces.countCreatedSince(any())).thenReturn(1L);
        when(messages.countByStatusSince(eq(MessageStatus.FAILED), any())).thenReturn(2L);
        when(messages.countByStatusSince(eq(MessageStatus.BOUNCED), any())).thenReturn(1L);
        when(messages.countByStatusSince(eq(MessageStatus.SENT), any())).thenReturn(500L);
        when(campaigns.countAbortedSince(any())).thenReturn(1L);

        OpsSignalsView s = service.signals();

        assertThat(s.workspaces()).isEqualTo(3);
        assertThat(s.signups7d()).isEqualTo(1);
        assertThat(s.suspended()).isEqualTo(1);
        assertThat(s.suspendedWorkspaces()).extracting(OpsWorkspaceRow::id).containsExactly(WS);
        assertThat(s.inFlight()).isEqualTo(1);
        assertThat(s.inFlightCampaigns().get(0).sent()).isEqualTo(5);
        assertThat(s.failed24h()).isEqualTo(2);
        assertThat(s.sent24h()).isEqualTo(500);
        assertThat(s.aborted24h()).isEqualTo(1);
    }

    @Test
    void 검색은_상한을_넘는_limit을_자르고_빈_검색어를_null로_넘긴다() {
        when(campaigns.search(isNull(), isNull(), isNull(), anyInt())).thenReturn(List.of());

        service.searchCampaigns(null, null, "   ", 10_000);

        verify(campaigns).search(null, null, null, PlatformOpsService.MAX_SEARCH);
    }

    // ── 시드 ─────────────────────────────────────────────────────────────

    @Test
    void 시드는_있는_계정에만_부여하고_이미_운영자면_건너뛴다() {
        User fresh = User.register("a@x.com", "h", null);
        User already = User.register("b@x.com", "h", null);
        already.setPlatformRole(User.PLATFORM_OPERATOR);
        when(users.findByEmail("a@x.com")).thenReturn(Optional.of(fresh));
        when(users.findByEmail("b@x.com")).thenReturn(Optional.of(already));
        when(users.findByEmail("nobody@x.com")).thenReturn(Optional.empty());

        int granted = service.seedOperators(List.of(" A@x.com ", "b@x.com", "nobody@x.com", ""));

        assertThat(granted).isEqualTo(1);
        assertThat(fresh.isPlatformOperator()).isTrue();
        verify(users).save(fresh);
        verify(users, never()).save(already);
    }

    private static Campaign campaign(long id, CampaignStatus status) {
        Campaign c = Campaign.draft("제목", "<p>본문</p>");
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setStatus(status);
        c.setCreatedAt(Instant.now());
        return c;
    }
}
