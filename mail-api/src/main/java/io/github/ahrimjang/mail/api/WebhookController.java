package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.BounceNotification;
import io.github.ahrimjang.mail.core.service.BounceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic provider webhook endpoint for asynchronous bounce/complaint
 * notifications. Providers post a normalized {@link BounceNotification} JSON,
 * authenticated by a shared secret carried in the {@code X-Webhook-Token}
 * header. Accepted notifications are handed to {@link BounceService}.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebhookController.class);

    private final BounceService bounceService;
    private final String secret;
    private final io.github.ahrimjang.mail.api.webhook.SnsSignatureVerifier snsVerifier;
    private final io.github.ahrimjang.mail.api.webhook.SnsSubscriptionConfirmer snsConfirmer;
    private final io.github.ahrimjang.mail.api.webhook.SesNotificationParser sesParser;
    private final boolean verifySnsSignature;
    // 우리 SES→SNS 토픽만 수용한다. 서명이 유효해도(= 진짜 Amazon 이 보낸) 다른 AWS
    // 계정의 토픽이면 거른다 — 아니면 순차 messageId 로 남의 테넌트 메시지를 BOUNCED 처리·
    // 억제·자동정지시킬 수 있다(AUDIT SEC-2). 비우면 무제한(개발 기본) — 운영은 반드시 설정.
    private final java.util.Set<String> allowedTopicArns;

    public WebhookController(BounceService bounceService,
                             io.github.ahrimjang.mail.api.webhook.SnsSignatureVerifier snsVerifier,
                             io.github.ahrimjang.mail.api.webhook.SnsSubscriptionConfirmer snsConfirmer,
                             io.github.ahrimjang.mail.api.webhook.SesNotificationParser sesParser,
                             @Value("${app.webhook.secret:dev-webhook-secret}") String secret,
                             @Value("${app.webhook.sns.verify-signature:true}") boolean verifySnsSignature,
                             @Value("${app.webhook.sns.topic-arn:}") String topicArns) {
        this.bounceService = bounceService;
        this.snsVerifier = snsVerifier;
        this.snsConfirmer = snsConfirmer;
        this.sesParser = sesParser;
        this.secret = secret;
        this.verifySnsSignature = verifySnsSignature;
        this.allowedTopicArns = (topicArns == null || topicArns.isBlank())
                ? java.util.Set.of()
                : java.util.Arrays.stream(topicArns.split(","))
                        .map(String::trim).filter(s -> !s.isBlank())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * SES notifications delivered through SNS. The endpoint is public
     * (SecurityConfig permits /api/webhooks/**), so authenticity comes from
     * Amazon's message signature, not a shared secret. SNS posts the JSON with
     * a text/plain content type — hence the raw String body.
     */
    @PostMapping(value = "/ses", consumes = org.springframework.http.MediaType.ALL_VALUE)
    public ResponseEntity<Void> ses(@RequestBody String rawBody) {
        io.github.ahrimjang.mail.api.webhook.SnsMessage sns;
        try {
            sns = io.github.ahrimjang.mail.api.webhook.SnsMessage.parse(rawBody);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (verifySnsSignature && !snsVerifier.isValid(sns)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 서명이 유효해도 우리 토픽이 아니면 거른다(설정 시). 구독 확인도 같은 게이트를
        // 통과해야 — 아니면 공격자 토픽 구독을 확인해줄 수 있다.
        if (!allowedTopicArns.isEmpty() && !allowedTopicArns.contains(sns.topicArn())) {
            log.warn("sns 거부: 허용되지 않은 topicArn {}", sns.topicArn());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        switch (sns.type() == null ? "" : sns.type()) {
            case "SubscriptionConfirmation" -> {
                // First contact from a new topic: fetching SubscribeURL activates it.
                if (!snsConfirmer.confirm(sns.subscribeUrl())) {
                    return ResponseEntity.badRequest().build();
                }
            }
            case "Notification" -> sesParser.parse(sns.message()).forEach(bounceService::handle);
            default -> { /* UnsubscribeConfirmation etc. — acknowledge and ignore */ }
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/generic")
    public ResponseEntity<Void> generic(
            @RequestHeader(value = "X-Webhook-Token", required = false) String token,
            @RequestBody BounceNotification notification) {
        if (secret == null || !secret.equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        bounceService.handle(notification);
        return ResponseEntity.accepted().build();
    }
}
