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

    public WebhookController(BounceService bounceService,
                             @Value("${app.webhook.secret:dev-webhook-secret}") String secret) {
        this.bounceService = bounceService;
        this.secret = secret;
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
