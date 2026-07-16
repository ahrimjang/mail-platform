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

    private final BounceService bounceService;
    private final String secret;
    private final io.github.ahrimjang.mail.api.webhook.SnsSignatureVerifier snsVerifier;
    private final io.github.ahrimjang.mail.api.webhook.SnsSubscriptionConfirmer snsConfirmer;
    private final io.github.ahrimjang.mail.api.webhook.SesNotificationParser sesParser;
    private final boolean verifySnsSignature;

    public WebhookController(BounceService bounceService,
                             io.github.ahrimjang.mail.api.webhook.SnsSignatureVerifier snsVerifier,
                             io.github.ahrimjang.mail.api.webhook.SnsSubscriptionConfirmer snsConfirmer,
                             io.github.ahrimjang.mail.api.webhook.SesNotificationParser sesParser,
                             @Value("${app.webhook.secret:dev-webhook-secret}") String secret,
                             @Value("${app.webhook.sns.verify-signature:true}") boolean verifySnsSignature) {
        this.bounceService = bounceService;
        this.snsVerifier = snsVerifier;
        this.snsConfirmer = snsConfirmer;
        this.sesParser = sesParser;
        this.secret = secret;
        this.verifySnsSignature = verifySnsSignature;
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
