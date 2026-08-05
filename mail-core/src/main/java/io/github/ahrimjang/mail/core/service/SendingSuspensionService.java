package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
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
 * <p>규칙: 최근 7일 발송 시도(SENT+BOUNCED)가 {@value #MIN_ATTEMPTED}건 이상이고
 * 바운스 비율이 {@value #SUSPEND_RATE} 이상이면 정지. 표본 하한이 있어 소량 발송의
 * 우연한 바운스로는 정지되지 않는다. 정지는 신규 캠페인 등록·트랜잭셔널만 막고
 * 진행 중 캠페인은 끝까지 나간다(발송 중 컷오프 금지 원칙). 해제는 운영자가 원인
 * 확인 후 수동(workspaces.sending_suspended_at 을 null 로).
 */
@Service
public class SendingSuspensionService {

    private static final Logger log = LoggerFactory.getLogger(SendingSuspensionService.class);
    static final int MIN_ATTEMPTED = 50;
    static final double SUSPEND_RATE = 0.10;
    private static final Duration WINDOW = Duration.ofDays(7);

    private final WorkspaceRepository workspaces;
    private final MailMessageRepository messages;

    public SendingSuspensionService(WorkspaceRepository workspaces, MailMessageRepository messages) {
        this.workspaces = workspaces;
        this.messages = messages;
    }

    /** 워커 경로: 바운스/컴플레인 반영 직후 호출 — 임계 초과 시 정지 처리(멱등). */
    public void checkAfterBounce(Long workspaceId) {
        try {
            Workspace workspace = workspaces.findById(workspaceId).orElse(null);
            if (workspace == null || workspace.isSendingSuspended()) {
                return;
            }
            var stats = messages.workspaceBounceStats(workspaceId, Instant.now().minus(WINDOW));
            if (stats.attempted() < MIN_ATTEMPTED || stats.bounceRate() < SUSPEND_RATE) {
                return;
            }
            workspace.setSendingSuspendedAt(Instant.now());
            workspace.setSuspensionReason(String.format(
                    "최근 7일 바운스율 %.1f%% (%d/%d) — 자동 정지", stats.bounceRate() * 100,
                    stats.bounced(), stats.attempted()));
            workspaces.save(workspace);
            log.warn("발송 자동 정지: workspace={} {}", workspaceId, workspace.getSuspensionReason());
        } catch (Exception e) {
            // 방어선이 바운스 처리 자체를 죽이면 안 된다
            log.error("발송 정지 판정 실패: workspace={}", workspaceId, e);
        }
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
