package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.SubscribeRequest;
import io.github.ahrimjang.mail.core.service.InvalidApiKeyException;
import io.github.ahrimjang.mail.core.service.PlanLimitExceededException;
import io.github.ahrimjang.mail.core.service.PublicSubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 외부 구독 신청 공개 API — 고객 사이트의 구독 폼이 호출한다.
 * 인증은 X-Api-Key 헤더(워크스페이스 API 키, 관리 페이지에서 발급).
 *
 * <pre>curl -X POST https://.../api/public/subscribe \
 *   -H "X-Api-Key: opk_..." -H "Content-Type: application/json" \
 *   -d '{"email":"reader@example.com","firstName":"길동","listId":1}'</pre>
 */
@RestController
@RequestMapping("/api/public")
public class PublicSubscribeController {

    private final PublicSubscriptionService subscriptions;

    public PublicSubscribeController(PublicSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    /** 201 = 신규 등록, 200 = 이미 등록된 주소(리스트 가입만 보장 — 멱등). */
    @PostMapping("/subscribe")
    public ResponseEntity<Map<String, Boolean>> subscribe(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody SubscribeRequest request) {
        boolean created = subscriptions.subscribe(apiKey, request);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Map.of("created", created));
    }

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<Map<String, String>> unauthorized(InvalidApiKeyException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PlanLimitExceededException.class)
    public ResponseEntity<Map<String, String>> planLimit(PlanLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
