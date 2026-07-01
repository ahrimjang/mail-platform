# 실제 바운스 처리 설계 — 웹훅 수신 → suppression 반영

## 배경 / 문제
바운스의 **대다수는 비동기**다. 메일 서버가 발송 시점엔 `250 OK`로 받아놓고, 나중에(수초~수시간 뒤)
배달 실패를 발견해 **별도 경로로 통보**한다. 통보 경로는 둘:
- **프로바이더 웹훅** (SES→SNS, SendGrid/ Mailgun Event Webhook) — 본 설계의 주 대상
- **반송 메일(DSN/NDR)** — 자체 MTA(Postfix) 운영 시. 인바운드 메일 파싱이 필요(대안).

목표: **웹훅 수신 → 하드 바운스/스팸신고 주소를 suppression에 반영**(재발송 차단). 부가로 해당 메시지를
`BOUNCED` 표시 + 지표 집계.

## 설계 원칙
1. **헥사고날**: 프로바이더별 JSON 파싱·서명검증은 **어댑터(mail-api)**, 코어는 **정규화된 이벤트**만 받는다.
2. **이메일 기반 suppression이 1차 목표** — 메시지 상관관계(correlation) 없이도 동작해야 한다.
3. **멱등성**: 웹훅은 재시도된다. `SuppressionRepository.save`는 이미 `existsByEmail` 체크로 멱등.
4. **보안**: 공개 엔드포인트지만 **프로바이더 서명 검증** 필수(아무나 POST해서 주소를 억제시키면 안 됨).

## 구성 요소

### 1) 정규화 도메인 (mail-common / mail-core)
```
BounceType (enum):  HARD_BOUNCE, SOFT_BOUNCE, COMPLAINT, DELIVERED(선택)
NormalizedEvent  :  { String email, BounceType type, String reason, String providerMessageId? }
```
프로바이더가 뭐든, 어댑터가 이 형태로 변환해 코어에 넘긴다.

### 2) 코어 서비스 (mail-core) — `BounceService`
```
void handle(NormalizedEvent e):
  switch (e.type):
    HARD_BOUNCE, COMPLAINT -> suppressions.save(Suppression.of(e.email, e.type))   // 억제
    SOFT_BOUNCE            -> (MVP) 무시/카운트만                                    // 일시 오류는 재시도 대상
    DELIVERED              -> (선택) EmailEvent(DELIVERED) 기록                       // 지표용
  // (correlation 있으면) providerMessageId로 MailMessage 찾아 markBounced + EmailEvent(BOUNCE)
```
기존 `SuppressionRepository`를 그대로 재사용 — 포트 확장 없음(이메일 기반 경로).

### 3) 웹훅 어댑터 (mail-api) — `WebhookController`
```
POST /api/webhooks/{provider}     예: /api/webhooks/ses, /api/webhooks/sendgrid
  1. 서명/시크릿 검증 (프로바이더별)
  2. raw payload -> 프로바이더 파서 -> List<NormalizedEvent>
  3. 각 이벤트를 BounceService.handle(...) 로 위임
```
- 프로바이더별 파서는 인터페이스로 추상화: `WebhookParser.parse(body, headers) -> List<NormalizedEvent>` +
  `verify(body, headers)`.
- `SecurityConfig` permitAll에 `/api/webhooks/**` 추가(단, 서명검증으로 실질 보호).

### 4) 보안 / 검증 (프로바이더별)
- **SES(SNS)**: SNS 메시지 **서명 검증** + `SubscriptionConfirmation` 자동 확인(구독 승인).
- **SendGrid**: Event Webhook **ECDSA 서명**(공개키) 검증, 또는 공유 시크릿.
- **최소(dev/테스트)**: `X-Webhook-Token` 헤더를 설정값과 비교.

### 5) 상관관계(correlation) — 메시지 단위 정확도 [2단계, 선택]
이메일 기반 suppression은 correlation 없이 되지만, **어느 캠페인의 어느 메시지가 바운스됐는지** 정확히
집계하려면 상관 키가 필요하다(한 주소가 여러 캠페인에 있으므로).
- **발송 시 커스텀 헤더 주입**: `X-Mail-Message-Id: {messageId}` (또는 기존 토큰 재사용).
  SES/SendGrid는 바운스 통보에 원본 헤더/`custom_args`를 **echo**해준다 → 웹훅이 읽어 상관.
- 또는 `MailMessage.providerMessageId` 필드 추가 → 발송 응답의 프로바이더 메시지 id 저장 후 매칭.
- 이걸로 **정확한 per-campaign 바운스 지표 + 메시지 BOUNCED 갱신** 가능.

### 6) 하드/소프트/컴플레인 정책
| 종류 | 예 | 처리 |
|------|-----|------|
| HARD / Permanent | 없는 주소, 도메인 없음 | **즉시 suppress** |
| COMPLAINT | 스팸 신고 | **즉시 suppress** |
| SOFT / Transient | 메일함 꽉참, 일시 오류 | suppress 안 함(재시도 대상), MVP는 카운트만 |

### 7) 이벤트 스트림 통합 (미래 Kafka와 연결)
프로바이더 이벤트(DELIVERED/BOUNCE/COMPLAINT)를 **`EmailEvent`에 타입 추가**로 기록하면
open/click과 **같은 스트림**이 된다. 나중에 이 스트림을 Kafka로 뺄 때 자연스럽게 흡수됨.

## 흐름
```
[메일 프로바이더]  (비동기, 발송 몇 초~몇 시간 뒤)
      │  POST 바운스/컴플레인 통보
      ▼
POST /api/webhooks/{provider}   ── 서명검증 실패시 401 ──▶ (거부)
      │  파서: raw → NormalizedEvent[]
      ▼
BounceService.handle(event)
      ├─ HARD/COMPLAINT → SuppressionRepository.save(email)   → 이후 발송 시 자동 SKIP
      ├─ SOFT           → 카운트(재시도 대상)
      └─ (correlation)  → MailMessage.markBounced + EmailEvent(BOUNCE)
```

## MVP 범위 vs 후속
- **MVP**: 웹훅 엔드포인트 + **1개 프로바이더** 파서 + 서명검증 + **이메일 기반 suppression**(HARD/COMPLAINT).
- **후속**: 메시지 correlation(정확 지표), soft-bounce N회 정책, DELIVERED 지표, 다중 프로바이더, DSN 메일 파싱.

## 테스트 전략 (dev)
MailHog는 웹훅을 안 보낸다 → **샘플 프로바이더 JSON을 `curl`로 POST**해서 검증한다.
예: SES bounce notification 샘플 → `/api/webhooks/ses` → 해당 주소가 suppression에 등록되는지 + 다음 캠페인에서
SKIP 되는지 확인.

## 구현 시 신규/변경 파일 (예상)
- **mail-common**: `BounceType` enum (correlation 시 `EventType`에 BOUNCE/COMPLAINT/DELIVERED 추가)
- **mail-core**: `BounceService` (+ correlation 시 `MailMessageRepository.findByProviderMessageId`)
- **mail-api**: `WebhookController` + `WebhookParser`(프로바이더 어댑터) + 서명검증; `SecurityConfig` permitAll `/api/webhooks/**`
- **infra**: (correlation 시) `MailMessageEntity.providerMessageId` 컬럼; 발송 시 커스텀 헤더 주입(`SmtpMailSender`)
- **config**: 프로바이더 시크릿/공개키

## 결정 (확정)
1. **프로바이더**: **일반 정규화 엔드포인트 먼저** — 우리가 정의한 JSON + 공유 시크릿(`X-Webhook-Token`) 검증.
   프로바이더 무관하게 코어를 완성하고 curl로 즉시 검증. 운영 시 **SES(SNS) 어댑터**를 추가(코어 불변).
2. **correlation 포함**: 발송 시 `X-Mail-Message-Id` 헤더 주입 + 웹훅 payload에 `messageId` 포함 →
   해당 `MailMessage`를 `BOUNCED`로 갱신 + `EmailEvent(BOUNCE)` 기록 + suppression 반영(3중).

### 확정 스코프에 따른 신규/변경 파일
- **mail-common**: `BounceType` enum(HARD_BOUNCE, SOFT_BOUNCE, COMPLAINT); `EventType`에 `BOUNCE` 추가;
  웹훅 요청 DTO `BounceNotification(email, type, reason, messageId?)`
- **mail-core**: `BounceService`(정규화 이벤트 처리); `EmailEventRepository` 재사용;
  `MailMessage.markBounced`는 이미 존재 — 재사용
- **mail-api**: `WebhookController`(`POST /api/webhooks/generic`, `X-Webhook-Token` 검증);
  `SecurityConfig` permitAll `/api/webhooks/**`; 시크릿 설정 `app.webhook.secret`
- **infra**: `SmtpMailSender`에 `X-Mail-Message-Id` 헤더 주입(발송 시)
- **config**: `app.webhook.secret` (mail-api yml)

### 테스트 (dev, curl)
```
# 1) 발송 → MailHog 메일 헤더에 X-Mail-Message-Id 확인
# 2) 바운스 웹훅 시뮬레이션
curl -X POST localhost:8080/api/webhooks/generic \
  -H "X-Webhook-Token: <secret>" -H "Content-Type: application/json" \
  -d '{"email":"a@x.com","type":"HARD_BOUNCE","reason":"550 user unknown","messageId":1}'
# 3) 해당 메시지 BOUNCED + a@x.com suppression 등록 + 재발송 시 SKIP 확인
```
