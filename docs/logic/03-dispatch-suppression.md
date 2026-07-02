# 03. 발송 처리와 수신거부 — dispatchOne 한 건의 일생

## 1. 개요 (무엇을, 왜)

02 문서에서 RabbitMQ 리스너가 `dispatchOne(messageId)`를 호출하는 데까지 왔습니다. 이 문서는 그 **한 건**이 어떻게 처리되는지를 따라갑니다.

`MailDispatchService.dispatchOne`은 메시지 id 하나를 받아:

1. **멱등성 체크** — 이미 처리된(PENDING이 아닌) 행이면 아무것도 안 하고 리턴. 큐가 같은 잡을 두 번 배달해도 이중 발송이 없습니다.
2. **억제(suppression) 체크** — 수신거부했거나 반송된 주소면 보내지 않고 `SUPPRESSED`로 마킹.
3. **HTML 조립** — 개인화 변수 치환 → 링크를 클릭 추적 URL로 재작성 → 수신거부 푸터 → 오픈 픽셀 순으로 본문을 쌓습니다.
4. **발송 + 결과 기록** — 성공이면 `SENT`, 실패면 `BOUNCED` + 해당 주소를 억제 목록에 자동 등록.
5. **completeIfDrained** — 캠페인의 PENDING이 0이 되면 캠페인을 `COMPLETED`로 전환.

**억제 목록(suppression list)** 은 "다시는 보내면 안 되는 주소"의 전역 명단입니다. 수신거부 링크 클릭과 발송 실패(반송) 두 경로로 채워지고, 모든 캠페인의 발송 시점에 존중됩니다. 스팸 신고를 피하고 발신자 평판을 지키는, 메일 플랫폼의 필수 장치입니다.

## 2. 흐름

```
SendJob{id} 도착 (MailSendListener → dispatchOne)
      │
      ▼
 메시지 조회 ──없음──> 리턴
      │
 PENDING? ──아니오──> 리턴 (멱등: 재배달/중복 스킵)
      │예
 캠페인 조회 ──없음──> markFailed → 저장 → 리턴
      │예
 캠페인 SENDING 전환 (첫 진행 시 1회)
      │
 억제 목록에 있는 주소? ──예──> SUPPRESSED 마킹 ─┐
      │아니오                                    │
 본문 조립: 변수치환 → 링크재작성 → 푸터 → 픽셀   │
      │                                          │
 sender.send(...) ──성공──> SENT ────────────────┤
      │실패                                       │
 BOUNCED 마킹 + 주소를 억제 목록에 추가 ──────────┤
                                                 ▼
                              completeIfDrained: pending==0 → 캠페인 COMPLETED

[수신거부 경로]  메일 푸터의 링크 클릭
  GET /api/unsubscribe/{token} → SuppressionService → 억제 목록 저장
  → 이후 모든 캠페인에서 이 주소는 SUPPRESSED
```

## 3. 단계별 실제 코드

### 3-1. 멱등성 가드 — 두 번 와도 한 번만 보낸다

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
    public void dispatchOne(Long messageId) {
        MailMessage message = messages.findById(messageId).orElse(null);
        if (message == null) {
            return;
        }
        if (message.getStatus() != MessageStatus.PENDING) {
            return;   // idempotency: skip redelivered/processed
        }
        Campaign campaign = campaigns.findById(message.getCampaignId()).orElse(null);
        if (campaign == null) {
            message.markFailed("campaign no longer exists");
            messages.save(message);
            return;
        }
        markSending(campaign);
```

RabbitMQ는 at-least-once라 같은 `SendJob`이 재배달될 수 있습니다. 그때 DB의 상태가 이미 `SENT`라면 여기서 조용히 빠져나갑니다. **진실은 큐가 아니라 DB에 있다**는 원칙의 실현입니다.

### 3-2. 억제 체크 — 보내기 전에 명단 확인

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
        if (suppressions.existsByEmail(message.getRecipient())) {
            message.markSuppressed();
            messages.save(message);
            completeIfDrained(campaign.getId());
            return;
        }
```

억제된 주소는 발송 시도조차 하지 않고 `SUPPRESSED`로 기록됩니다. 실패도 성공도 아닌 별도 상태로 남겨서 캠페인 통계(`suppressed` 카운트)에 그대로 드러납니다.

### 3-3. HTML 조립 — 변수 치환, 추적 링크, 푸터, 픽셀

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
        String subject = campaign.getSubject();
        String bodySrc = campaign.getBody();
        Map<String, String> vars = Map.of("email", message.getRecipient());
        if (message.getContactId() != null) {
            vars = contacts.findById(message.getContactId()).map(Contact::toVariables).orElse(vars);
        }
        subject = templateRenderer.render(subject, vars);
        bodySrc = templateRenderer.render(bodySrc, vars);
        String trackedBody = trackingRewriter.rewriteLinks(bodySrc, message.getTrackingToken(), baseUrl);
        String html = trackedBody + unsubscribeFooter(message.getUnsubToken())
                + trackingRewriter.openPixel(message.getTrackingToken(), baseUrl);
```

레이어가 차곡차곡 쌓입니다: ① 연락처 기반 개인화 변수(`{{name}}` 등) 치환 → ② 본문 안의 `href` 링크를 클릭 추적 리다이렉트 URL로 재작성 → ③ 수신거부 푸터 덧붙임 → ④ 마지막에 1×1 오픈 픽셀. 토큰(`trackingToken`, `unsubToken`)은 캠페인 생성 시 메시지 행마다 미리 만들어져 있으므로, 여기서는 조립만 합니다.

푸터 자체도 이 파일에 있습니다:

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
    private String unsubscribeFooter(String token) {
        return "<hr><p style=\"font-size:12px;color:#888\">더 이상 받지 않으려면 "
                + "<a href=\"" + baseUrl + "/api/unsubscribe/" + token + "\">수신거부</a></p>";
    }
```

### 3-4. 발송과 결과 기록 — SENT / BOUNCED + 자동 억제

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
        try {
            sender.send(message.getRecipient(), subject, html, String.valueOf(message.getId()));
            message.markSent();
        } catch (Exception e) {
            log.warn("send failed: campaign={} recipient={} reason={}",
                    campaign.getId(), message.getRecipient(), e.getMessage());
            message.markBounced(e.getMessage());
            suppressions.save(Suppression.of(message.getRecipient(), "bounce"));
        }
        messages.save(message);
        completeIfDrained(campaign.getId());
```

실패하면 `BOUNCED`로 마킹하는 데서 끝나지 않고 **그 주소를 즉시 억제 목록에 넣습니다**("bounce" 사유). 다음 캠페인부터는 시도조차 안 하게 됩니다 — 죽은 주소에 반복 발송하면 발신자 평판이 깎이기 때문입니다.

### 3-5. 캠페인 완료 판정 — completeIfDrained

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
    private void completeIfDrained(Long campaignId) {
        MessageCounts counts = messages.countByCampaign(campaignId);
        if (counts.pending() == 0) {
            campaigns.updateStatus(campaignId, CampaignStatus.COMPLETED);
        }
    }
```

"내가 마지막 한 건이었나?"를 매번 DB 카운트로 물어봅니다. 별도의 완료 이벤트나 카운터 없이, **PENDING이 0이면 완료**라는 단순한 규칙 하나로 캠페인 상태(`QUEUED → SENDING → COMPLETED`)가 굴러갑니다.

### 3-6. 수신거부 — 토큰 → 억제 목록

푸터의 링크를 누르면 이 공개 엔드포인트(로그인 불필요, `SecurityConfig`의 `permitAll`)로 옵니다.

`mail-api/src/main/java/io/github/ahrimjang/mail/api/UnsubscribeController.java`
```java
    @GetMapping(value = "/api/unsubscribe/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String unsubscribe(@PathVariable String token) {
        suppressions.suppressByUnsubToken(token);
        return "<html><body style=\"font-family:system-ui;text-align:center;padding:3rem\">" +
                "<h2>수신거부 완료</h2><p>더 이상 이 메일을 받지 않습니다.</p></body></html>";
    }
```

토큰을 주소로 바꿔 저장하는 로직은 core에 있습니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/SuppressionService.java`
```java
    /** Suppress the address behind an unsubscribe token, if the token resolves. */
    public void suppressByUnsubToken(String token) {
        messages.findByUnsubToken(token)
                .ifPresent(m -> suppressions.save(Suppression.of(m.getRecipient(), "unsubscribe")));
    }
```

URL에 이메일 주소 대신 **랜덤 토큰**을 쓰는 이유: 주소를 노출하지 않고, 남이 URL을 조작해 아무 주소나 수신거부시키는 것을 막기 위해서입니다. 토큰이 없으면(`ifPresent`) 조용히 무시합니다. 억제 도메인 객체는 단순한 POJO입니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/domain/Suppression.java`
```java
    /** Factory for a newly suppressed address. */
    public static Suppression of(String email, String reason) {
        Suppression s = new Suppression();
        s.email = email;
        s.reason = reason;
        s.createdAt = Instant.now();
        return s;
    }
```

### 3-7. 발송 어댑터 — SMTP와 로깅, 설정으로 교체

실제 발송은 `MailSender` 포트 뒤의 어댑터가 합니다. worker는 `mail.sender.type=smtp`라 SMTP 구현이 뜹니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/mail/SmtpMailSender.java`
```java
@Component
@ConditionalOnProperty(name = "mail.sender.type", havingValue = "smtp")
public class SmtpMailSender implements MailSender {
```
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
```

`setText(body, true)`의 `true`가 "HTML로 보내라"는 뜻입니다. `X-Mail-Message-Id` 헤더에 우리 메시지 id를 실어 보내는데, 반송 웹훅이 어떤 메시지의 반송인지 역추적할 때 씁니다.

api/admin 프로세스는 발송할 일이 없으므로 기본값인 로깅 구현이 뜹니다(속성 없으면 `matchIfMissing = true`).

`infra/src/main/java/io/github/ahrimjang/mail/infra/mail/LoggingMailSender.java`
```java
@Component
@ConditionalOnProperty(name = "mail.sender.type", havingValue = "logging", matchIfMissing = true)
public class LoggingMailSender implements MailSender {
```
```java
    @Override
    public void send(String recipient, String subject, String body, String messageId) throws MailSendException {
        if (recipient == null || !recipient.contains("@")) {
            throw new MailSendException("invalid recipient address: " + recipient);
        }
        log.info("[MAIL] -> {} | subject=\"{}\" | bodyChars={} | messageId={}",
                recipient, subject, body == null ? 0 : body.length(), messageId);
    }
```

두 구현 모두 `@`가 없는 주소를 일부러 예외로 던집니다 — BOUNCED 경로를 손쉽게 시험해 보기 위한 장치입니다.

## 4. 설계 포인트 (왜 이렇게)

- **멱등 소비자**: at-least-once 큐 앞에서는 "중복이 와도 안전한 소비자"가 정답입니다. 상태 체크(`!= PENDING → 스킵`) 하나로 해결. 단, 조회와 저장 사이에 락이 없으므로 워커 여러 대가 동시에 같은 id를 잡으면 이중 발송 여지가 있습니다(프로덕션은 `SELECT ... FOR UPDATE SKIP LOCKED` 등이 필요) — CLAUDE.md에 명시된 POC 한계입니다.
- **메시지 상태는 "전달 결과"만**: `PENDING → SENT | FAILED | BOUNCED | SUPPRESSED`. 열람/클릭은 상태가 아니라 별도 `EmailEvent`로 쌓습니다. 한 메시지가 "보내졌고 + 열렸고 + 클릭됐다"는 다차원 사실을 단일 상태로 욱여넣지 않기 위해서입니다.
- **억제는 전역, 두 경로로 유입**: 수신거부(명시적 거절)와 반송(죽은 주소) 모두 같은 명단으로 갑니다. `reason` 필드("unsubscribe"/"bounce")로 유입 경로는 구분됩니다.
- **발송기는 속성 하나로 교체**: `@ConditionalOnProperty` 덕분에 SES/SendGrid 어댑터를 `infra`에 추가하고 `mail.sender.type`만 바꾸면 프로덕션 발송으로 전환됩니다. core는 무변경.

## 5. 확인 방법

MailHog + RabbitMQ + mail-api + mail-worker를 띄운 뒤(토큰은 01 문서 참고):

```bash
# 정상 주소 2건 + 고장난 주소 1건이 섞인 캠페인
curl -s -X POST http://localhost:8080/api/campaigns \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"subject":"test","body":"<p>hello</p>","recipients":["ok1@test.com","ok2@test.com","broken"]}'

# 잠시 후 조회 → sent=2, bounced=1, pending=0, status=COMPLETED
curl -s http://localhost:8080/api/campaigns/1 -H "Authorization: Bearer $TOKEN"

# MailHog(http://localhost:8025)에서 도착한 메일을 열어 푸터의 수신거부 링크를 클릭하거나:
curl -s http://localhost:8080/api/unsubscribe/<메일 푸터의 토큰>
# → "수신거부 완료" HTML

# 같은 주소로 새 캠페인을 만들면 이번엔 suppressed 카운트로 잡힌다
curl -s -X POST http://localhost:8080/api/campaigns \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"subject":"again","body":"<p>hi</p>","recipients":["ok1@test.com"]}'
curl -s http://localhost:8080/api/campaigns/2 -H "Authorization: Bearer $TOKEN"
# → suppressed=1, sent=0
```

체크 포인트: ① `broken` 주소가 `bounced`로 집계되는지, ② 반송/수신거부된 주소가 **다음 캠페인에서 자동으로 `suppressed`** 되는지, ③ MailHog에서 메일 본문 맨 아래에 수신거부 푸터와 (소스 보기 시) 오픈 픽셀 `<img>` 태그가 붙어 있는지. worker 콘솔 로그에서도 `send failed: ...` 경고를 확인할 수 있습니다.
