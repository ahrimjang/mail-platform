package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 발송 평판 방어 — 바운스·컴플레인 비율이 임계를 넘은 워크스페이스의 신규 발송을
 * 자동 정지한다. SES 평판(발송 도메인 전체)은 테넌트 하나의 스팸으로도 무너지므로,
 * 개별 주소 억제(suppression)보다 한 층 위의 방어선이 필요하다.
 *
 * <p><b>축이 둘이다 — 바운스와 스팸 신고는 임계가 자릿수부터 다르다.</b> 둘 중 하나만
 * 넘어도 정지한다. 창은 둘 다 최근 7일, 분모는 발송 시도(SENT+BOUNCED)다.
 *
 * <ol>
 *   <li><b>바운스</b>: 시도 {@value #MIN_ATTEMPTED}건 이상 + 비율 {@value #SUSPEND_RATE} 이상.
 *       "없는 주소로 보내고 있다"는 명단 품질 신호라 임계가 느슨해도 된다.</li>
 *   <li><b>스팸 신고</b>: 시도 {@value #MIN_ATTEMPTED_COMPLAINT}건 이상 +
 *       신고 {@value #MIN_COMPLAINTS}건 이상 + 비율 {@value #COMPLAINT_SUSPEND_RATE} 이상.
 *       SES 는 계정 컴플레인율 0.5% 를 정지선으로 보므로, 바운스 기준(10%)에 묻어 두면
 *       SES 가 우리 계정을 정지시킨 뒤에야 우리 가드가 켜진다. 대신 분모가 작을 때
 *       신고 한 건이 바로 임계를 넘기므로(50건 중 1건 = 2%), 표본·절대 건수 하한을
 *       바운스보다 높게 잡아 소량 발송자가 오탐으로 정지되지 않게 한다.</li>
 * </ol>
 *
 * <p>정지는 신규 캠페인 등록·트랜잭셔널만 막고 진행 중 캠페인은 끝까지 나간다(발송 중
 * 컷오프 금지 원칙). 해제는 운영자가 원인 확인 후 수동(workspaces.sending_suspended_at
 * 을 null 로).
 */
@Service
public class SendingSuspensionService {

    private static final Logger log = LoggerFactory.getLogger(SendingSuspensionService.class);
    static final int MIN_ATTEMPTED = 50;
    static final double SUSPEND_RATE = 0.10;
    /** 스팸 신고 축의 표본 하한 — 바운스보다 높다(비율 임계가 두 자릿수 작아서). */
    static final int MIN_ATTEMPTED_COMPLAINT = 200;
    /** 신고 한두 건의 우연으로 정지되지 않게 하는 절대 건수 하한. */
    static final int MIN_COMPLAINTS = 3;
    /** SES 정지선(0.5%)보다 낮게 — 우리가 먼저 멈춰야 계정이 안 죽는다. */
    static final double COMPLAINT_SUSPEND_RATE = 0.003;
    /** 컴플레인 억제 행의 reason — BounceService 가 BounceType.COMPLAINT 를 이 문자열로 적는다. */
    private static final String COMPLAINT_REASON = "complaint";
    private static final Duration WINDOW = Duration.ofDays(7);

    private final WorkspaceRepository workspaces;
    private final MailMessageRepository messages;
    private final SuppressionRepository suppressions;

    public SendingSuspensionService(WorkspaceRepository workspaces, MailMessageRepository messages,
                                    SuppressionRepository suppressions) {
        this.workspaces = workspaces;
        this.messages = messages;
        this.suppressions = suppressions;
    }

    /** 워커 경로: 바운스/컴플레인 반영 직후 호출 — 임계 초과 시 정지 처리(멱등). */
    public void checkAfterBounce(Long workspaceId) {
        try {
            Workspace workspace = workspaces.findById(workspaceId).orElse(null);
            if (workspace == null || workspace.isSendingSuspended()) {
                return;
            }
            Instant since = Instant.now().minus(WINDOW);
            var stats = messages.workspaceBounceStats(workspaceId, since);
            String reason = suspensionReason(workspaceId, since, stats);
            if (reason == null) {
                return;
            }
            workspace.setSendingSuspendedAt(Instant.now());
            workspace.setSuspensionReason(reason);
            workspaces.save(workspace);
            log.warn("발송 자동 정지: workspace={} {}", workspaceId, workspace.getSuspensionReason());
        } catch (Exception e) {
            // 방어선이 바운스 처리 자체를 죽이면 안 된다
            log.error("발송 정지 판정 실패: workspace={}", workspaceId, e);
        }
    }

    /**
     * 두 축을 차례로 본다. 정지할 사유가 없으면 null.
     *
     * <p>바운스를 먼저 보는 건 우선순위가 아니라 비용 때문이다 — 바운스 통계는 이미 읽었고,
     * 신고 건수는 쿼리가 한 번 더 나간다. 바운스로 이미 정지될 상황이면 굳이 더 묻지 않는다.
     */
    private String suspensionReason(Long workspaceId, Instant since,
                                    MailMessageRepository.WorkspaceBounceStats stats) {
        if (stats.attempted() >= MIN_ATTEMPTED && stats.bounceRate() >= SUSPEND_RATE) {
            return String.format("최근 7일 바운스율 %.1f%% (%d/%d) — 자동 정지",
                    stats.bounceRate() * 100, stats.bounced(), stats.attempted());
        }
        if (stats.attempted() < MIN_ATTEMPTED_COMPLAINT) {
            return null;
        }
        long complaints = complaintsSince(workspaceId, since);
        if (complaints < MIN_COMPLAINTS) {
            return null;
        }
        double rate = (double) complaints / stats.attempted();
        if (rate < COMPLAINT_SUSPEND_RATE) {
            return null;
        }
        // 소수 둘째 자리까지 — 0.3% 대를 다루므로 %.1f 면 "0.3%" 와 "0.34%" 가 같아 보인다
        return String.format("최근 7일 스팸 신고율 %.2f%% (%d/%d) — 자동 정지",
                rate * 100, complaints, stats.attempted());
    }

    /** 최근 창의 스팸 신고 수 — 억제 목록의 reason 이 곧 신고 기록이다(별도 집계 테이블 없음). */
    private long complaintsSince(Long workspaceId, Instant since) {
        return suppressions.countByReasonSince(workspaceId, since).stream()
                .filter(r -> COMPLAINT_REASON.equalsIgnoreCase(r.reason()))
                .mapToLong(SuppressionRepository.ReasonCount::count)
                .sum();
    }

    /**
     * 정지 사유 — 정지 상태가 아니면 빈 값.
     *
     * <p>작성 화면이 정지 사실을 미리 알리기 위한 읽기 전용 조회. 집행은
     * {@link #assertNotSuspended} 가 담당한다.
     */
    public java.util.Optional<String> suspensionReasonOf(Long workspaceId) {
        return workspaces.findById(workspaceId)
                .filter(Workspace::isSendingSuspended)
                // 사유가 비어 있어도 "정지 아님"으로 읽히면 안 된다 — 기본 문구로 채운다
                .map(w -> w.getSuspensionReason() == null || w.getSuspensionReason().isBlank()
                        ? "반송·신고 비율이 높아 발송이 일시 정지됐어요."
                        : w.getSuspensionReason());
    }

    /** 발송 경로 게이트 — 정지된 워크스페이스는 409 로 이어지는 IllegalStateException. */
    public void assertNotSuspended(Long workspaceId) {
        workspaces.findById(workspaceId)
                .filter(Workspace::isSendingSuspended)
                .ifPresent(w -> {
                    throw new IllegalStateException(
                            "반송·신고 비율이 높아 발송이 일시 정지됐어요. 수신자 명단을 점검한 뒤 문의해주세요.");
                });
    }
}
