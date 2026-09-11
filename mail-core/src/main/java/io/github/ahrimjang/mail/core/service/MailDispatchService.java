package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.SendRateLimiter;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import io.github.ahrimjang.mail.core.domain.Suppression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Sends one queued message per invocation. Invoked by the worker's queue listener.
 *
 * <p>One call = load the message by id, send it via the {@link MailSender}
 * port, and record the outcome. Campaigns flip to SENDING on first progress
 * and to COMPLETED once their queue is fully drained.
 *
 * <p>Because the queue is at-least-once, redeliveries are expected — and because
 * multiple consumers can race on the same messageId (redelivery landing on a
 * different worker while the first is still mid-send), a status check alone
 * ("skip if not PENDING") is not enough: two callers can both read PENDING
 * before either writes back, and both send. The handler instead opens with
 * {@link MailMessageRepository#claim}, a single atomic conditional update
 * (PENDING -&gt; SENDING) — only one caller can win it. A claim stuck in SENDING
 * past its staleness window is reclaimable too, so a crashed consumer's message
 * is still recovered by the next redelivery instead of getting stuck forever.
 *
 * <p>Before the claim, the workspace's send throttle is consulted
 * ({@link SendRateLimiter}): a throttled message is bounced to a short delay
 * queue and comes back unclaimed, so one tenant at its rate cap never blocks
 * other tenants' traffic on the shared queue.
 */
@Service
public class MailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MailDispatchService.class);

    /** How long a SENDING claim is honored before it's considered abandoned (crashed consumer) and reclaimable. */
    private static final Duration STALE_CLAIM_AFTER = Duration.ofMinutes(2);

    private final MailMessageRepository messages;
    private final CampaignRepository campaigns;
    private final MailSender sender;
    private final SuppressionRepository suppressions;
    private final TrackingRewriter trackingRewriter;
    private final TemplateRenderer templateRenderer;
    private final ContactRepository contacts;
    private final SendRateLimiter rateLimiter;
    private final MailQueue queue;
    private final NotificationService notifications;
    private final String baseUrl;

    public MailDispatchService(MailMessageRepository messages,
                               CampaignRepository campaigns,
                               MailSender sender,
                               SuppressionRepository suppressions,
                               TrackingRewriter trackingRewriter,
                               TemplateRenderer templateRenderer,
                               ContactRepository contacts,
                               SendRateLimiter rateLimiter,
                               MailQueue queue,
                               NotificationService notifications,
                               @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.messages = messages;
        this.campaigns = campaigns;
        this.notifications = notifications;
        this.sender = sender;
        this.suppressions = suppressions;
        this.trackingRewriter = trackingRewriter;
        this.templateRenderer = templateRenderer;
        this.contacts = contacts;
        this.rateLimiter = rateLimiter;
        this.queue = queue;
        this.baseUrl = baseUrl;
    }

    /**
     * Process a single queued message by id.
     *
     * <p>Idempotent: a redelivery that loses the {@link MailMessageRepository#claim}
     * race (already claimed/processed elsewhere) is skipped without effect.
     */
    public void dispatchOne(Long messageId) {
        MailMessage message = messages.findById(messageId).orElse(null);
        if (message == null) {
            return;
        }
        if (message.getStatus() != MessageStatus.PENDING && message.getStatus() != MessageStatus.SENDING) {
            // Redelivery of an already-finished message — don't spend a token on it.
            return;
        }
        Campaign campaign = campaigns.findById(message.getCampaignId()).orElse(null);
        // 억제 확인은 토큰 소비보다 앞에(ARCH-10). 억제된 주소는 어차피 안 나가는데 발송
        // 토큰을 먼저 쓰면 억제 30% 명단에서 발송 예산 30% 가 허비된다. 종료 기록은 여전히
        // claim 을 거쳐 조건부로 쓴다.
        if (campaign != null && suppressions.existsByWorkspaceAndEmail(campaign.getWorkspaceId(), message.getRecipient())) {
            java.util.Optional<java.time.Instant> claimed = messages.claim(messageId, STALE_CLAIM_AFTER);
            if (claimed.isEmpty()) {
                return;
            }
            markSending(campaign);
            message.markSuppressed();
            finish(message, claimed.get());
            completeIfDrained(campaign.getId());
            return;
        }
        // Tenant throttle, checked BEFORE the claim: a throttled message must stay
        // PENDING so its delayed redelivery claims it normally — claiming first
        // would strand it in SENDING until the stale-claim window expires.
        if (campaign != null && !rateLimiter.tryAcquire(campaign.getWorkspaceId())) {
            queue.enqueueThrottled(messageId);
            return;
        }
        // claim 토큰(claim 이 찍은 updatedAt) — 종료 기록은 이 토큰이 아직 유효할 때만 쓴다
        java.util.Optional<java.time.Instant> claimed = messages.claim(messageId, STALE_CLAIM_AFTER);
        if (claimed.isEmpty()) {
            log.debug("skip: message {} already claimed/processed by another consumer", messageId);
            return;
        }
        java.time.Instant claimedAt = claimed.get();
        if (campaign == null) {
            message.markFailed("campaign no longer exists");
            finish(message, claimedAt);
            return;
        }
        markSending(campaign);
        String subject = campaign.getSubject();
        String bodySrc = campaign.getBody();
        // A/B: a held (variant-null) message of a decided campaign renders the winner.
        String variant = message.getVariant();
        if (variant == null && campaign.getAbWinner() != null) {
            variant = campaign.getAbWinner();
        }
        if ("B".equals(variant)) {
            // A/B variant B: swap in the B subject/body where provided (a null B body
            // means a subject-only test — the body stays shared).
            if (campaign.getAbSubjectB() != null) {
                subject = campaign.getAbSubjectB();
            }
            if (campaign.getAbBodyB() != null) {
                bodySrc = campaign.getAbBodyB();
            }
        }
        // 직접 입력 수신자(연락처 없음)도 {{name}} 이 빈칸으로 나가지 않게 — 이메일 아이디로 대체
        Map<String, String> vars = Map.of(
                "email", message.getRecipient(),
                "name", Contact.displayName(message.getRecipient(), null, null));
        if (message.getContactId() != null) {
            vars = contacts.findById(message.getContactId()).map(Contact::toVariables).orElse(vars);
        }
        subject = templateRenderer.render(subject, vars);
        bodySrc = templateRenderer.render(bodySrc, vars);
        String trackedBody = trackingRewriter.rewriteLinks(bodySrc, message.getTrackingToken(), baseUrl);
        String html = trackedBody + unsubscribeFooter(message.getUnsubToken())
                + trackingRewriter.openPixel(message.getTrackingToken(), baseUrl);
        // 본문 링크와 같은 주소를 헤더로도 내보낸다 — 메일 앱의 수신거부 버튼이 이걸 쓴다
        var options = new MailSender.Options(
                campaign.getReplyTo(), unsubscribeUrl(message.getUnsubToken()));
        try {
            sender.send(message.getRecipient(), subject, html, String.valueOf(message.getId()),
                    campaign.getSenderName(), campaign.getSenderEmail(), options);
            message.markSent();
        } catch (Exception e) {
            // ERROR + throwable so the failure is observable: the stack trace ships to
            // OpenSearch, letting the log dashboard surface it and map it back to source.
            log.error("send failed: campaign={} recipient={}",
                    campaign.getId(), message.getRecipient(), e);
            message.markBounced(e.getMessage());
            suppressions.save(Suppression.of(campaign.getWorkspaceId(), message.getRecipient(), "bounce"));
        }
        finish(message, claimedAt);
        completeIfDrained(campaign.getId());
    }

    /**
     * 종료 상태를 조건부로 기록한다(ARCH-4). blind save 는 늦게 끝난 쪽이 무조건 이겨서,
     * SMTP 가 오래 걸려 다른 워커가 stale 재클레임한 뒤에도 이쪽의 옛 결과가 새 결과를
     * 덮어썼고, 바운스 웹훅이 먼저 BOUNCED 를 쓴 것도 SENT 로 되돌렸다. 토큰이 안 맞으면
     * 0행 — 그건 "내 결과는 이미 무효"라는 뜻이라 로그만 남긴다.
     */
    private void finish(MailMessage message, java.time.Instant claimedAt) {
        boolean recorded = messages.finish(message.getId(), claimedAt, message.getStatus(),
                message.getErrorMessage(), message.getUpdatedAt());
        if (!recorded) {
            log.warn("종료 기록 건너뜀 — claim 토큰 불일치(재클레임 또는 바운스 선반영): message={} status={}",
                    message.getId(), message.getStatus());
        }
    }

    /** 수신거부 진입 주소 — 본문 링크와 List-Unsubscribe 헤더가 같은 곳을 가리킨다. */
    private String unsubscribeUrl(String token) {
        return baseUrl + "/api/unsubscribe/" + token;
    }

    private String unsubscribeFooter(String token) {
        return "<hr><p style=\"font-size:12px;color:#888\">더 이상 받지 않으려면 "
                + "<a href=\"" + unsubscribeUrl(token) + "\">수신거부</a></p>";
    }

    private void markSending(Campaign campaign) {
        // QUEUED -> SENDING only. A list campaign is EXPANDING here — fan-out owns its
        // EXPANDING -> SENDING flip — so this is a no-op for it.
        campaigns.markSendingIfQueued(campaign.getId());
    }

    /**
     * 캠페인에 남은 PENDING/SENDING 이 없으면 완료로 전이한다. 발송 경로뿐 아니라 DLQ 처리
     * (재시도 소진 → FAILED 확정)도 이걸 불러야 한다 — 마지막 메시지가 DLQ 로 빠진
     * 캠페인은 여기서 마무리해 주지 않으면 영원히 "발송 중"에 머문다.
     */
    public void completeIfDrained(Long campaignId) {
        // Cheap EXISTS instead of a full per-status count on every send: a campaign
        // with any PENDING/SENDING left is still draining. completeIfSending only
        // fires from SENDING, so a campaign mid-EXPANDING is never completed early.
        if (!messages.hasPendingOrSending(campaignId)) {
            // 전이를 이긴 호출만 true — 워커 다중 기동에도 완료 알림은 정확히 1건
            if (campaigns.completeIfSending(campaignId)) {
                campaigns.findById(campaignId).ifPresent(notifications::campaignCompleted);
            }
        }
    }
}
