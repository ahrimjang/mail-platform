# 05. 비동기 바운스 웹훅 — 반송 통보를 받아 억제 목록에 반영하기

## 1. 개요 — 무엇을, 왜

**바운스(bounce)** 는 "이 주소로는 배달 못 했다"는 통보입니다. 문제는 타이밍입니다:

- **동기 바운스**: SMTP 발송 순간 서버가 즉시 거절(예: `550 user unknown`). 이건
  `MailDispatchService`의 `try/catch`가 이미 처리합니다(`markBounced` + 즉시 suppression).
- **비동기 바운스**: 발송 시점엔 수신 서버가 일단 `250 OK`로 받아놓고, **수초~수시간 뒤**에
  배달 실패를 발견해 별도 경로로 알려주는 경우. 실제 바운스의 대다수가 이쪽입니다.
  SES/SendGrid 같은 프로바이더는 이를 **웹훅(우리 서버로의 HTTP POST)** 으로 통보합니다.

즉 우리 API는 "보내는" 쪽인데, 바운스는 "받는" 쪽 — 프로바이더가 우리를 호출하는 역방향
흐름입니다. 이 문서는 그 수신 경로를 다룹니다: 웹훅 수신 → 시크릿 검증 → 정규화된 통보를
`BounceService`에 위임 → (1) 주소 suppression 등록, (2) 해당 메시지 `BOUNCED` 갱신,
(3) `EmailEvent(BOUNCE)` 기록. 설계 배경 전체는 `docs/bounce-webhook-design.md` 참고.

한 가지 퍼즐: 통보에는 보통 이메일 주소만 있는데, 같은 주소가 여러 캠페인에 있으면
**"어느 캠페인의 어느 메시지가 반송됐는지"** 를 모릅니다. 그래서 발송 시 메일 헤더에
`X-Mail-Message-Id: {messageId}`를 심어두고, 프로바이더가 이를 echo해 주면 웹훅 payload의
`messageId`로 정확히 상관(correlation)시킵니다.

## 2. 흐름

```
[발송 시 — mail-worker]
SmtpMailSender.send ──▶ MIME 헤더에 X-Mail-Message-Id: {messageId} 주입

[나중에 — 프로바이더 → mail-api]  (dev에선 curl로 시뮬레이션)
POST /api/webhooks/generic  (JSON: email, type, reason, messageId?)
      │
      ├─ X-Webhook-Token ≠ 시크릿 ──▶ 401 (거부)
      ▼
BounceService.handle(notification)
      ├─ messageId 있음 → MailMessage.markBounced + EmailEvent(BOUNCE)   (멱등)
      ├─ HARD_BOUNCE / COMPLAINT → Suppression.save(email)               (멱등)
      └─ SOFT_BOUNCE → 아무것도 안 함 (일시 오류, 재시도 대상)
      ▼
202 Accepted
      ▼
다음 발송 때 MailDispatchService 가 suppression 조회 → 해당 주소 SUPPRESSED 로 SKIP
```

## 3. 단계별 실제 코드

### 3-1. 정규화된 통보 형태 — mail-common의 DTO

프로바이더마다 JSON이 제각각이므로, 코어는 **우리가 정의한 정규화 형태**만 받습니다.
(프로바이더별 파싱/서명검증은 어댑터 계층의 일 — 헥사고날 원칙.)

`mail-common/src/main/java/io/github/ahrimjang/mail/common/BounceType.java`
```java
/**
 * Classification of an asynchronous bounce/complaint reported by a provider.
 *
 * <p>{@code HARD_BOUNCE} and {@code COMPLAINT} are permanent problems that
 * suppress the address; {@code SOFT_BOUNCE} is transient and retryable.
 */
public enum BounceType {
    HARD_BOUNCE,
    SOFT_BOUNCE,
    COMPLAINT,
}
```

`mail-common/src/main/java/io/github/ahrimjang/mail/common/BounceNotification.java`
```java
/**
 * Normalized bounce/complaint notification accepted at the generic webhook.
 *
 * @param email     the address that bounced or complained
 * @param type      classification of the problem (permanent vs. transient)
 * @param reason    provider-supplied human-readable reason
 * @param messageId optional correlation to a specific {@code MailMessage}; may be
 *                  {@code null} when the provider cannot echo back our message id
 */
public record BounceNotification(String email, BounceType type, String reason, Long messageId) {
}
```

`messageId`가 **optional**인 점이 중요합니다 — 상관 키가 없어도 이메일 기반 suppression은
동작해야 한다는 설계 원칙 때문입니다.

### 3-2. 상관 키 심기 — 발송 시 X-Mail-Message-Id 헤더

`infra/src/main/java/io/github/ahrimjang/mail/infra/mail/SmtpMailSender.java`
```java
    @Override
    public void send(String recipient, String subject, String body, String messageId) throws MailSendException {
        if (recipient == null || !recipient.contains("@")) {
            throw new MailSendException("invalid recipient address: " + recipient);
        }
        MimeMessage msg = mailSender.createMimeMessage();
        try {
            MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
            h.setTo(recipient);
            h.setSubject(subject);
            h.setText(body, true);
            if (messageId != null) {
                msg.setHeader("X-Mail-Message-Id", messageId);
            }
            mailSender.send(msg);
        } catch (Exception e) {
            throw new MailSendException("failed to send to " + recipient + ": " + e.getMessage(), e);
        }
        log.info("[SMTP] -> {} | subject=\"{}\" | bodyChars={}",
                recipient, subject, body == null ? 0 : body.length());
    }
```

호출하는 쪽(워커)이 메시지 id를 문자열로 넘깁니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
            sender.send(message.getRecipient(), subject, html, String.valueOf(message.getId()));
```

실제 SES/SendGrid는 바운스 통보에 원본 커스텀 헤더(또는 custom_args)를 **echo**해 주므로,
운영에선 프로바이더 어댑터가 이 헤더값을 꺼내 `BounceNotification.messageId`에 채우게 됩니다.

### 3-3. 웹훅 엔드포인트 — 공유 시크릿 검증

`mail-api/src/main/java/io/github/ahrimjang/mail/api/WebhookController.java`
```java
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
```

- 프로바이더는 JWT 로그인을 할 수 없으므로 이 경로는 `SecurityConfig`에서 permitAll이지만,
  대신 **`X-Webhook-Token` 헤더 = 설정된 시크릿(`app.webhook.secret`)** 일 때만 처리합니다.
  아무나 POST해서 남의 주소를 억제시키는 것을 막는 최소한의 방어입니다.
- 성공 응답이 `202 Accepted`인 것도 웹훅 관례 — "접수했다"만 알리면 프로바이더는 재시도를 멈춥니다.
- 운영에선 이 "generic" 엔드포인트 옆에 SES(SNS 서명 검증), SendGrid(ECDSA 서명) 같은
  프로바이더별 어댑터를 추가하면 되고, 코어(`BounceService`)는 그대로입니다.

### 3-4. 코어 처리 — BounceService (억제 + BOUNCED + 이벤트)

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/BounceService.java`
```java
    public void handle(BounceNotification n) {
        // 1) correlation (optional) — mark the specific message BOUNCED + record event, idempotently
        if (n.messageId() != null) {
            messages.findById(n.messageId()).ifPresent(m -> {
                if (m.getStatus() != MessageStatus.BOUNCED) {
                    m.markBounced(n.reason());
                    messages.save(m);
                    events.save(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.BOUNCE, null));
                }
            });
        }
        // 2) email-based suppression for permanent problems (always; save is idempotent via existsByEmail)
        if (n.type() == BounceType.HARD_BOUNCE || n.type() == BounceType.COMPLAINT) {
            suppressions.save(Suppression.of(n.email(), n.type().name().toLowerCase()));
        }
        // SOFT_BOUNCE: transient — do nothing (retryable)
    }
```

두 갈래가 **독립적으로** 수행됩니다:

1. **메시지 상관(있을 때만)**: 해당 `MailMessage`를 `BOUNCED`로 바꾸고 `EmailEvent(BOUNCE)`를
   남깁니다 → 캠페인별 bounced 지표가 정확해집니다. 이미 `BOUNCED`면 건너뜀(멱등).
2. **주소 억제(영구 문제일 때)**: HARD_BOUNCE/COMPLAINT면 suppression 목록에 등록 →
   이후 모든 캠페인에서 이 주소는 발송 전에 SKIP됩니다. 이미 등록된 주소는 어댑터의
   `existsByEmail` 체크로 중복 저장되지 않음(멱등).

웹훅은 프로바이더가 **재시도**하는 것이 정상이라(네트워크 오류 등), 같은 통보가 두 번
와도 결과가 같아야 합니다 — 그래서 두 경로 모두 멱등입니다.

### 3-5. soft/hard/complaint 정책

| 종류 | 예 | 처리 |
|------|-----|------|
| `HARD_BOUNCE` (영구) | 없는 주소, 도메인 없음 | 즉시 suppress + (상관 시) BOUNCED |
| `COMPLAINT` (스팸 신고) | 수신자가 "스팸" 버튼 클릭 | 즉시 suppress — 계속 보내면 발신 평판 급락 |
| `SOFT_BOUNCE` (일시) | 메일함 꽉 참, 일시 장애 | **아무것도 안 함** — 재시도 가능해야 하므로 억제 금지 |

소프트 바운스를 억제해 버리면 "메일함이 잠깐 찼던 정상 고객"을 영영 잃습니다. 후속 과제는
"소프트 바운스 N회 연속 시 억제" 같은 카운트 정책입니다(`docs/bounce-webhook-design.md`의 후속 범위).

## 4. 설계 포인트 — 왜 이렇게

- **generic 엔드포인트부터.** 특정 프로바이더 JSON을 파싱하기 전에, 우리가 정의한 정규화
  DTO + 공유 시크릿으로 코어 로직을 완성해 curl로 즉시 검증할 수 있게 했습니다. SES/SendGrid
  어댑터는 나중에 "raw payload → BounceNotification 변환기"만 추가하면 됩니다(코어 불변).
- **suppression이 1차, correlation은 2차.** 발송 차단(평판 보호)이 지표 정확도보다
  급하므로, `messageId`가 없어도 이메일 기반 억제는 항상 동작하게 두 갈래를 분리했습니다.
- **BOUNCE도 EmailEvent 스트림에.** open/click과 같은 이벤트 테이블에 쌓아서(EventType에
  `BOUNCE` 추가) 나중에 이 스트림을 Kafka 등으로 뺄 때 한 번에 흡수됩니다.
- **멱등성은 웹훅의 기본 계약.** 프로바이더는 2xx를 못 받으면 재전송합니다. 상태 체크
  (`!= BOUNCED`)와 `existsByEmail` 덕에 몇 번을 다시 받아도 안전합니다.
- **dev 한계**: MailHog는 웹훅을 보내지 않으므로 비동기 바운스는 curl 시뮬레이션으로만
  검증합니다. 또 공유 시크릿 비교는 dev 수준 — 운영은 프로바이더 서명 검증(SNS 서명,
  ECDSA)으로 교체해야 합니다.

## 5. 확인 방법 (curl)

```bash
# 0) MailHog + mail-api + mail-worker 기동 후, 캠페인 하나 발송해 둔다 (04 문서 참고)

# 1) MailHog(http://localhost:8025)에서 메일 소스 열기
#    → 헤더에 X-Mail-Message-Id: 1 같은 값이 보이면 상관 키 주입 OK

# 2) 하드 바운스 웹훅 시뮬레이션 (시크릿 기본값: dev-webhook-secret)
curl -i -X POST localhost:8080/api/webhooks/generic \
  -H "X-Webhook-Token: dev-webhook-secret" -H "Content-Type: application/json" \
  -d '{"email":"a@x.com","type":"HARD_BOUNCE","reason":"550 user unknown","messageId":1}'
# → HTTP/1.1 202

# 3) 시크릿이 틀리면 거부되는지
curl -i -X POST localhost:8080/api/webhooks/generic \
  -H "X-Webhook-Token: wrong" -H "Content-Type: application/json" \
  -d '{"email":"a@x.com","type":"HARD_BOUNCE","reason":"x","messageId":1}'
# → HTTP/1.1 401

# 4) 소프트 바운스는 아무 효과 없음(202만 반환, 억제 안 됨)
curl -i -X POST localhost:8080/api/webhooks/generic \
  -H "X-Webhook-Token: dev-webhook-secret" -H "Content-Type: application/json" \
  -d '{"email":"b@x.com","type":"SOFT_BOUNCE","reason":"mailbox full"}'

# 5) 결과 확인
#    - GET /api/campaigns/{id} → bounced 카운트 증가 (messageId 상관 덕분)
#    - a@x.com 을 수신자로 새 캠페인 발송 → 해당 메시지가 SUPPRESSED 로 SKIP 되고
#      suppressed 카운트로 잡히는지 확인 (b@x.com 은 정상 발송되어야 함)
#    - 같은 웹훅을 한 번 더 POST 해도 카운트가 더 늘지 않음(멱등)
```
