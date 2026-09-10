package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * DLQ 로 빠진 잡의 뒷정리(AUDIT ARCH-3).
 *
 * <p>리스너가 예외를 3회 던지면 잡은 큐에서 제거돼 DLQ 로 간다. 그때까지 아무도 이걸
 * 처리하지 않았다: 발송 잡의 메시지는 SENDING 인 채 남고(재전달이 없으니 stale 재클레임도
 * 안 돈다) 캠페인은 영원히 "발송 중"이었다. 화면상으로는 "실패가 한 건도 없는" 것과
 * 구분되지 않았다 — docs/logic 은 "DLQ 만 모니터링하면 된다"고 했지만 그 수단이 코드에
 * 없었다.
 *
 * <p>여기서 하는 것: 발송 잡 → 메시지를 FAILED 로 확정하고 캠페인 완료 판정을 돌린다.
 * 팬아웃 잡 → 알림만(상태 복구는 복구 스위퍼의 몫, ARCH-1). 둘 다 워크스페이스에
 * 인앱 알림을 캠페인당 한 번 보낸다.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    /** 같은 캠페인의 실패 알림은 이 간격에 한 번 — 포이즌 메시지 1,000건이 알림 1,000건이 되지 않게. */
    static final Duration NOTIFY_WINDOW = Duration.ofHours(1);

    private final MailMessageRepository messages;
    private final CampaignRepository campaigns;
    private final MailDispatchService dispatch;
    private final NotificationService notifications;

    // 워커는 단일 인스턴스라 인메모리로 충분하다. 다중 기동 시 최악은 중복 알림이고
    // 발송 상태에는 영향이 없다.
    private final Map<Long, Instant> notifiedAt = new ConcurrentHashMap<>();

    public DeadLetterService(MailMessageRepository messages, CampaignRepository campaigns,
                             MailDispatchService dispatch, NotificationService notifications) {
        this.messages = messages;
        this.campaigns = campaigns;
        this.dispatch = dispatch;
        this.notifications = notifications;
    }

    /**
     * 발송 잡이 DLQ 로 왔다. 아직 종료되지 않은 메시지만 FAILED 로 확정한다 — 이미
     * SENT/BOUNCED 등이면(재전달과 DLQ 유입이 겹친 경우) 손대지 않는다.
     */
    public void sendJobDead(Long messageId, String reason) {
        MailMessage message = messages.findById(messageId).orElse(null);
        if (message == null) {
            log.warn("DLQ 발송 잡의 메시지가 없다: messageId={}", messageId);
            return;
        }
        if (message.getStatus() != MessageStatus.PENDING && message.getStatus() != MessageStatus.SENDING) {
            log.info("DLQ 발송 잡 무시 — 이미 종료됨: messageId={} status={}", messageId, message.getStatus());
            return;
        }
        message.markFailed("발송 처리가 반복 실패해 중단됐어요 (" + reason + ")");
        messages.save(message);
        log.error("DLQ: 발송 잡 FAILED 확정 — messageId={} campaign={} recipient={} reason={}",
                messageId, message.getCampaignId(), message.getRecipient(), reason);
        // 이 메시지가 마지막이었다면 캠페인을 마무리한다 — 안 하면 영원히 "발송 중"
        dispatch.completeIfDrained(message.getCampaignId());
        notifyOnce(message.getCampaignId(), notifications::campaignSendFailed);
    }

    /**
     * 팬아웃 잡이 DLQ 로 왔다 — 캠페인의 수신자 확장이 반복 실패해 발송이 시작되지 못했다.
     * 상태(EXPANDING/QUEUED)를 여기서 되돌리지는 않는다: 커서 재개가 필요한 복구는
     * 스위퍼(ARCH-1)가 맡고, 여기서는 관측 공백만 메운다.
     */
    public void fanoutJobDead(Long campaignId, String reason) {
        log.error("DLQ: 팬아웃 잡 실패 — campaign={} reason={}", campaignId, reason);
        notifyOnce(campaignId, notifications::campaignFanoutFailed);
    }

    private void notifyOnce(Long campaignId, Consumer<Campaign> publish) {
        Instant now = Instant.now();
        Instant last = notifiedAt.get(campaignId);
        if (last != null && last.plus(NOTIFY_WINDOW).isAfter(now)) {
            return;
        }
        notifiedAt.put(campaignId, now);
        campaigns.findById(campaignId).ifPresent(publish);
    }
}
