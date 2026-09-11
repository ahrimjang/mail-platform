package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.common.OpsAuditEntry;
import io.github.ahrimjang.mail.common.OpsCampaignRow;
import io.github.ahrimjang.mail.common.OpsSignalsView;
import io.github.ahrimjang.mail.common.OpsWorkspaceDetail;
import io.github.ahrimjang.mail.common.OpsWorkspaceRow;
import io.github.ahrimjang.mail.common.WorkspaceUserView;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.domain.PlatformAuditEntry;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.WorkspaceStatusCount;
import io.github.ahrimjang.mail.core.port.PlatformAuditRepository;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 플랫폼 운영자 콘솔(/ops) — 테넌트를 넘나드는 조회와 조치. 매뉴얼 5·6절이 psql 로 하던
 * 정지/해제·플랜 조정·캠페인 중단을 화면으로 옮긴 것이다.
 *
 * <p>이 서비스는 **테넌트 격리 원칙의 의도적 예외**다. 그래서 세 가지를 지킨다:
 * 모든 진입점이 {@link WorkspaceContext#isPlatformOperator()} 를 먼저 본다(403),
 * 워크스페이스는 ctx 가 아니라 명시 인자로 받는다, 변경은 전부 감사 로그에 남긴다.
 * 조치 자체는 기존 도메인 규칙을 그대로 쓴다 — 정지 컬럼, 플랜 적용의 속도 상한 클램프,
 * 캠페인 abort 의 조건부 UPDATE 는 콘솔 경로와 같은 코드다.
 */
@Service
public class PlatformOpsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformOpsService.class);
    static final Duration BOUNCE_WINDOW = Duration.ofDays(30);
    static final int RECENT_CAMPAIGNS = 20;
    static final int MAX_SEARCH = 100;
    static final int MAX_AUDIT = 200;

    private final WorkspaceContext ctx;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final PlatformAuditRepository audit;
    private final NotificationService notifications;

    public PlatformOpsService(WorkspaceContext ctx, WorkspaceRepository workspaces, UserRepository users,
                              CampaignRepository campaigns, MailMessageRepository messages,
                              PlatformAuditRepository audit, NotificationService notifications) {
        this.ctx = ctx;
        this.workspaces = workspaces;
        this.users = users;
        this.campaigns = campaigns;
        this.messages = messages;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    /** 전체 워크스페이스 — 기본 정렬은 30일 바운스율 내림차순(문의가 날 곳이 위로). */
    public List<OpsWorkspaceRow> workspaces() {
        requireOperator();
        Stats stats = loadStats();
        return workspaces.findAll().stream()
                .map(w -> rowOf(w, stats))
                .sorted(Comparator.comparingDouble(OpsWorkspaceRow::bounceRate30d).reversed()
                        .thenComparing(OpsWorkspaceRow::createdAt, Comparator.reverseOrder()))
                .toList();
    }

    public OpsWorkspaceDetail workspace(Long id) {
        requireOperator();
        Workspace w = requireWorkspace(id);
        Stats stats = loadStats();
        List<WorkspaceUserView> members = users.findByWorkspaceId(id).stream()
                .map(u -> new WorkspaceUserView(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.getCreatedAt()))
                .toList();
        Map<Long, String> names = Map.of(id, w.getName());
        List<OpsCampaignRow> recent = campaigns.search(null, id, null, RECENT_CAMPAIGNS).stream()
                .map(c -> campaignRow(c, names))
                .toList();
        return new OpsWorkspaceDetail(rowOf(w, stats), w.getSendRatePerSec(), w.getPlan().sendRateCap(),
                members, recent);
    }

    /** 전 테넌트 캠페인 검색 — 상태·워크스페이스·검색어 전부 선택. */
    public List<OpsCampaignRow> searchCampaigns(CampaignStatus status, Long workspaceId, String q, int limit) {
        requireOperator();
        int capped = Math.max(1, Math.min(MAX_SEARCH, limit));
        String needle = q == null || q.isBlank() ? null : q.trim();
        Map<Long, String> names = workspaceNames();
        return campaigns.search(status, workspaceId, needle, capped).stream()
                .map(c -> campaignRow(c, names))
                .toList();
    }

    /** 운영 신호 — 정지·진행 중·최근 24시간 실패/바운스/중단. */
    public OpsSignalsView signals() {
        requireOperator();
        Instant now = Instant.now();
        Instant dayAgo = now.minus(Duration.ofHours(24));
        Stats stats = loadStats();
        Map<Long, String> names = workspaceNames();
        List<OpsWorkspaceRow> suspended = workspaces.findAll().stream()
                .filter(Workspace::isSendingSuspended)
                .map(w -> rowOf(w, stats))
                .toList();
        List<OpsCampaignRow> inFlight = campaigns.findInFlight().stream()
                .map(c -> campaignRow(c, names))
                .toList();
        return new OpsSignalsView(
                workspaces.count(),
                workspaces.countCreatedSince(now.minus(Duration.ofDays(7))),
                suspended.size(),
                inFlight.size(),
                messages.countByStatusSince(MessageStatus.FAILED, dayAgo),
                messages.countByStatusSince(MessageStatus.BOUNCED, dayAgo),
                messages.countByStatusSince(MessageStatus.SENT, dayAgo),
                campaigns.countAbortedSince(dayAgo),
                inFlight, suspended);
    }

    public List<OpsAuditEntry> audit(int limit) {
        requireOperator();
        return audit.findRecent(Math.max(1, Math.min(MAX_AUDIT, limit))).stream()
                .map(e -> new OpsAuditEntry(e.getId(), e.getActorEmail(), e.getAction(), e.getWorkspaceId(),
                        e.getCampaignId(), e.getDetail(), e.getCreatedAt()))
                .toList();
    }

    // ── 조치 ─────────────────────────────────────────────────────────────

    /** 발송 정지 — 사유 필수. 자동 정지(바운스율)와 같은 컬럼을 쓰므로 게이트 체인이 그대로 막는다. */
    public OpsWorkspaceRow suspend(Long id, String reason) {
        requireOperator();
        String why = requireReason(reason);
        Workspace w = requireWorkspace(id);
        if (w.isSendingSuspended()) {
            throw new IllegalStateException("이미 발송 정지 상태입니다: " + w.getSuspensionReason());
        }
        w.setSendingSuspendedAt(Instant.now());
        w.setSuspensionReason("운영자 정지: " + why);
        workspaces.save(w);
        record(PlatformAuditEntry.SUSPEND, id, null, why);
        notifications.sendingSuspended(id, why);
        log.warn("운영자 발송 정지: workspace={} by={} reason={}", id, ctx.currentUserEmail(), why);
        return rowOf(w, loadStats());
    }

    /** 정지 해제 — 자동 정지든 운영자 정지든. 해제 사유(원인 확인 내용)를 감사 로그에 남긴다. */
    public OpsWorkspaceRow unsuspend(Long id, String reason) {
        requireOperator();
        String why = requireReason(reason);
        Workspace w = requireWorkspace(id);
        if (!w.isSendingSuspended()) {
            throw new IllegalStateException("정지 상태가 아닙니다.");
        }
        String previous = w.getSuspensionReason();
        w.setSendingSuspendedAt(null);
        w.setSuspensionReason(null);
        workspaces.save(w);
        record(PlatformAuditEntry.UNSUSPEND, id, null, why + " (이전: " + previous + ")");
        notifications.sendingResumed(id);
        log.warn("운영자 정지 해제: workspace={} by={} reason={}", id, ctx.currentUserEmail(), why);
        return rowOf(w, loadStats());
    }

    /**
     * 플랜 수동 변경 — 결제 없이 적용한다(보상·체험·장애 보상·엔터프라이즈 계약). 셀프서비스
     * 경로(BillingService)와 달리 엔터프라이즈도 허용하고, 카드 등록도 요구하지 않는다.
     */
    public OpsWorkspaceRow changePlan(Long id, String planName, String reason) {
        requireOperator();
        String why = requireReason(reason);
        Plan target;
        try {
            target = Plan.valueOf(planName == null ? "" : planName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 플랜입니다: " + planName);
        }
        Workspace w = requireWorkspace(id);
        Plan before = w.getPlan();
        if (before == target) {
            throw new IllegalStateException("이미 " + target.name() + " 플랜입니다.");
        }
        w.changePlan(target);
        workspaces.save(w);
        record(PlatformAuditEntry.CHANGE_PLAN, id, null, before.name() + " → " + target.name() + ": " + why);
        notifications.planChanged(id, target.name());
        log.info("운영자 플랜 변경: workspace={} {}→{} by={}", id, before, target, ctx.currentUserEmail());
        return rowOf(w, loadStats());
    }

    /** 구독 API 키 폐기 — 유출 신고 등. 테넌트는 관리 화면에서 재발급한다. */
    public OpsWorkspaceRow revokeApiKey(Long id, String reason) {
        requireOperator();
        String why = requireReason(reason);
        Workspace w = requireWorkspace(id);
        if (w.getApiKey() == null) {
            throw new IllegalStateException("발급된 API 키가 없습니다.");
        }
        w.setApiKey(null);
        workspaces.save(w);
        record(PlatformAuditEntry.REVOKE_API_KEY, id, null, why);
        log.warn("운영자 API 키 폐기: workspace={} by={}", id, ctx.currentUserEmail());
        return rowOf(w, loadStats());
    }

    /** 운영자 발송 중단 — 콘솔의 abort 와 같은 조건부 UPDATE, 소유 검증 대신 감사 기록. */
    public OpsCampaignRow abortCampaign(Long id, String reason) {
        requireOperator();
        String why = requireReason(reason);
        Campaign c = campaigns.findById(id)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + id));
        if (!campaigns.abort(id)) {
            throw new IllegalStateException("이미 끝났거나 취소된 캠페인이라 중단할 수 없어요: " + id);
        }
        int canceled = messages.cancelPendingByCampaign(id);
        record(PlatformAuditEntry.ABORT_CAMPAIGN, c.getWorkspaceId(), id, why + " (남은 " + canceled + "건 취소)");
        log.warn("운영자 캠페인 중단: campaign={} workspace={} canceled={} by={}", id, c.getWorkspaceId(),
                canceled, ctx.currentUserEmail());
        Campaign after = campaigns.findById(id).orElse(c);
        return campaignRow(after, workspaceNames());
    }

    // ── 기동 시드 ─────────────────────────────────────────────────────────

    /**
     * 환경변수 목록의 이메일에 운영자 권한을 부여한다(멱등). 권한의 진실은 DB 컬럼이고
     * 환경변수는 부트스트랩일 뿐 — 목록에서 빠져도 회수하지 않는다(회수는 명시적으로).
     *
     * @return 이번에 새로 부여한 수
     */
    public int seedOperators(Collection<String> emails) {
        int granted = 0;
        for (String raw : emails) {
            String email = raw == null ? "" : raw.trim().toLowerCase();
            if (email.isEmpty()) {
                continue;
            }
            User u = users.findByEmail(email).orElse(null);
            if (u == null) {
                log.warn("플랫폼 운영자 시드: 계정 없음 — {} (가입 후 재기동하면 부여됨)", email);
                continue;
            }
            if (u.isPlatformOperator()) {
                continue;
            }
            u.setPlatformRole(User.PLATFORM_OPERATOR);
            users.save(u);
            granted++;
            log.info("플랫폼 운영자 부여: {}", email);
        }
        return granted;
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private void requireOperator() {
        if (!ctx.isPlatformOperator()) {
            throw new ForbiddenException("플랫폼 운영자 권한이 필요합니다.");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("사유를 입력해주세요 — 감사 로그와 테넌트 알림에 남습니다.");
        }
        return reason.trim();
    }

    private Workspace requireWorkspace(Long id) {
        return workspaces.findById(id)
                .orElseThrow(() -> new NoSuchElementException("workspace not found: " + id));
    }

    private void record(String action, Long workspaceId, Long campaignId, String detail) {
        audit.save(PlatformAuditEntry.of(ctx.currentUserEmail(), action, workspaceId, campaignId,
                detail.length() > 500 ? detail.substring(0, 500) : detail));
    }

    private Map<Long, String> workspaceNames() {
        return workspaces.findAll().stream()
                .collect(Collectors.toMap(Workspace::getId, Workspace::getName, (a, b) -> a));
    }

    /**
     * 목록 한 장에 필요한 집계를 쿼리 네 번으로 — 이번 달 발송, 30일 시도/바운스/최근 활동,
     * ADMIN·OPERATOR 사용자. 워크스페이스 수만큼 쿼리를 날리지 않기 위한 묶음이다.
     */
    private Stats loadStats() {
        ZoneId zone = ZoneId.systemDefault();
        Instant monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        Instant windowStart = Instant.now().minus(BOUNCE_WINDOW);

        Map<Long, Long> monthlySent = new HashMap<>();
        for (WorkspaceStatusCount c : messages.aggregateByWorkspaceSince(monthStart)) {
            if (c.status() == MessageStatus.SENT) {
                monthlySent.merge(c.workspaceId(), c.count(), Long::sum);
            }
        }
        Map<Long, long[]> window = new HashMap<>();   // [attempted, bounced]
        Map<Long, Instant> lastActivity = new HashMap<>();
        for (WorkspaceStatusCount c : messages.aggregateByWorkspaceSince(windowStart)) {
            long[] acc = window.computeIfAbsent(c.workspaceId(), k -> new long[2]);
            if (c.status() == MessageStatus.SENT || c.status() == MessageStatus.BOUNCED) {
                acc[0] += c.count();
            }
            if (c.status() == MessageStatus.BOUNCED) {
                acc[1] += c.count();
            }
            if (c.lastAt() != null) {
                lastActivity.merge(c.workspaceId(), c.lastAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        List<User> admins = users.findByRole("ADMIN");
        List<User> operators = users.findByRole("OPERATOR");
        Map<Long, User> owner = new HashMap<>();
        for (User u : admins) {
            owner.putIfAbsent(u.getWorkspaceId(), u);   // 가입순 정렬 — 첫 ADMIN 이 소유자
        }
        Map<Long, Long> memberCount = new HashMap<>();
        for (User u : admins) {
            memberCount.merge(u.getWorkspaceId(), 1L, Long::sum);
        }
        for (User u : operators) {
            memberCount.merge(u.getWorkspaceId(), 1L, Long::sum);
        }
        return new Stats(monthlySent, window, lastActivity, owner, memberCount);
    }

    private record Stats(Map<Long, Long> monthlySent, Map<Long, long[]> window, Map<Long, Instant> lastActivity,
                         Map<Long, User> owner, Map<Long, Long> memberCount) {
    }

    private static OpsWorkspaceRow rowOf(Workspace w, Stats s) {
        long[] win = s.window().getOrDefault(w.getId(), new long[2]);
        User owner = s.owner().get(w.getId());
        return new OpsWorkspaceRow(
                w.getId(), w.getName(), w.getPlan().name(), w.getCreatedAt(),
                s.memberCount().getOrDefault(w.getId(), 0L),
                owner == null ? null : owner.getEmail(),
                owner != null && owner.isEmailVerified(),
                s.monthlySent().getOrDefault(w.getId(), 0L),
                w.getPlan().monthlySendLimit(),
                win[0], win[1],
                s.lastActivity().get(w.getId()),
                w.getSendingSuspendedAt(), w.getSuspensionReason(),
                w.getBillingKey() != null, w.getApiKey() != null);
    }

    private OpsCampaignRow campaignRow(Campaign c, Map<Long, String> names) {
        MailMessageRepository.MessageCounts n = messages.countByCampaign(c.getId());
        return new OpsCampaignRow(c.getId(), c.getWorkspaceId(), names.get(c.getWorkspaceId()),
                c.getName(), c.getSubject(), c.getStatus(),
                n.total(), n.sent(), n.failed(), n.bounced(),
                c.getCreatedAt(), c.getEnqueuedAt(), c.getCompletedAt(), c.getCreatedBy());
    }
}
