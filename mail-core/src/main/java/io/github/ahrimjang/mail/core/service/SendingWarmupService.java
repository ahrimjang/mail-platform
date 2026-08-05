package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 신규 워크스페이스 발송 워밍업. 바운스율 자동 정지(V32)는 <b>이미 나간 결과</b>를
 * 보고 판정하므로, 쓰레기 명단 1,000통이 전부 발송된 뒤에야 작동한다 — 그 1,000통은
 * 이미 SES 계정 평판에 꽂힌 뒤다.
 *
 * <p>그래서 첫 발송은 소량으로 제한한다: 누적 발송이 {@value #WARMUP_THRESHOLD}통에
 * 못 미치는 워크스페이스는 캠페인 1건당 수신자를 {@value #WARMUP_BATCH}명까지만 등록할
 * 수 있다. 정상 사용자는 두어 번 나눠 보내는 동안 바운스율이 낮게 확인되며 자연히
 * 임계를 넘고, 어뷰저는 대량 발사 전에 정체가 드러난다.
 */
@Service
public class SendingWarmupService {

    static final int WARMUP_THRESHOLD = 200;   // 누적 발송이 이만큼 쌓이면 워밍업 졸업
    static final int WARMUP_BATCH = 50;        // 워밍업 중 캠페인 1건당 최대 수신자

    private final MailMessageRepository messages;
    private final boolean enabled;

    public SendingWarmupService(MailMessageRepository messages,
                                @Value("${app.mail.warmup-enabled:true}") boolean enabled) {
        this.messages = messages;
        this.enabled = enabled;
    }

    /**
     * 캠페인 등록 시점 게이트. 리스트 캠페인은 팬아웃 전이라 대상 수를 여기서 받는다
     * (호출자가 계산해 넘긴다).
     */
    public void assertBatchAllowed(Long workspaceId, long recipientCount) {
        if (!enabled || recipientCount <= WARMUP_BATCH) {
            return;
        }
        if (totalSent(workspaceId) >= WARMUP_THRESHOLD) {
            return;   // 워밍업 졸업 — 정상 발송 이력이 쌓였다
        }
        throw new PlanLimitExceededException(String.format(
                "첫 발송은 한 번에 %d명까지 보낼 수 있어요. 몇 번 나눠 보내 정상 발송이 확인되면(누적 %d통) 제한이 풀립니다. "
                        + "이 조치는 반송이 많은 명단으로 발송 평판이 상하는 것을 막기 위한 거예요.",
                WARMUP_BATCH, WARMUP_THRESHOLD));
    }

    /** 이 워크스페이스가 지금까지 실제로 발송한 총량 (계정 개설 이후 전체). */
    private long totalSent(Long workspaceId) {
        return messages.countSentByWorkspaceSince(workspaceId, java.time.Instant.EPOCH);
    }
}
