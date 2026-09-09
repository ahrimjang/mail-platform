package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.BounceNotification;
import io.github.ahrimjang.mail.common.BounceType;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.EmailEventPublisher;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Service;

/**
 * Applies asynchronous bounce/complaint notifications arriving from a provider webhook.
 *
 * <p>Permanent problems (hard bounce, complaint) suppress the address; when a
 * message id is correlated we also mark that message BOUNCED and publish a BOUNCE
 * event onto the async event stream. State changes (status, suppression) stay
 * synchronous; only the engagement event rides Kafka. Soft bounces are transient
 * and left untouched so delivery may be retried. The handler is idempotent: a
 * message already BOUNCED is skipped, and suppression saves are deduplicated by
 * the adapter.
 */
@Service
public class BounceService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BounceService.class);

    private final SuppressionRepository suppressions;
    private final MailMessageRepository messages;
    private final CampaignRepository campaigns;
    private final EmailEventPublisher events;
    private final SendingSuspensionService suspension;

    public BounceService(SuppressionRepository suppressions,
                         MailMessageRepository messages,
                         CampaignRepository campaigns,
                         EmailEventPublisher events,
                         SendingSuspensionService suspension) {
        this.suppressions = suppressions;
        this.messages = messages;
        this.campaigns = campaigns;
        this.events = events;
        this.suspension = suspension;
    }

    public void handle(BounceNotification n) {
        // 1) correlation (optional) — mark the specific message BOUNCED + record event, idempotently
        if (n.messageId() != null) {
            messages.findById(n.messageId()).ifPresent(m -> {
                if (m.getStatus() != MessageStatus.BOUNCED) {
                    m.markBounced(n.reason());
                    messages.save(m);
                    events.publish(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.BOUNCE, null));
                }
            });
        }
        // 2) email-based suppression for permanent problems. The suppression list
        // is per tenant, so the notification must be attributable: the message
        // correlation resolves the campaign's workspace. Without it we drop the
        // suppression rather than poison every tenant's list.
        if (n.type() == BounceType.HARD_BOUNCE || n.type() == BounceType.COMPLAINT) {
            Long workspaceId = n.messageId() == null ? null
                    : messages.findById(n.messageId())
                            .flatMap(m -> campaigns.findById(m.getCampaignId()))
                            .map(Campaign::getWorkspaceId)
                            .orElse(null);
            if (workspaceId != null) {
                suppressions.save(Suppression.of(workspaceId, n.email(), n.type().name().toLowerCase()));
                // 평판 방어 — 이 워크스페이스의 바운스율이 임계를 넘었으면 자동 정지
                suspension.checkAfterBounce(workspaceId);
            } else {
                // 여기서 조용히 버리면 "바운스가 한 건도 없는 것"과 구분되지 않는다.
                // 가장 흔한 원인은 SES 알림 설정의 "원본 헤더 포함"이 꺼져 있어
                // X-Mail-Message-Id 가 통보에 실려오지 않는 것 — 그러면 평판 가드
                // 전체(억제·자동정지)가 입력을 못 받는데 화면상 아무 징후가 없다.
                log.warn("바운스 통보를 상관 지을 수 없어 억제를 건너뜁니다 (type={}, email={}, messageId={}) "
                                + "— SES 알림에 원본 헤더 포함이 켜져 있는지 확인하세요.",
                        n.type(), n.email(), n.messageId());
            }
        }
        // SOFT_BOUNCE: transient — do nothing (retryable)
    }
}
