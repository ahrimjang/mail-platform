# 03. 발송 처리와 수신거부 — dispatchOne 한 건의 일생

## 1. 개요 (무엇을, 왜)

02 문서에서 RabbitMQ 리스너가 `dispatchOne(messageId)`를 호출하는 데까지 왔습니다. 이 문서는 그 **한 건**이 어떻게 처리되는지를 따라갑니다.

`MailDispatchService.dispatchOne`은 메시지 id 하나를 받아:

1. **억제(suppression) 체크** — 수신거부했거나 반송된 주소면 보내지 않고 `SUPPRESSED`로 마킹. 2026-09 부터 이 확인이 **토큰 소비보다 앞**입니다(어차피 안 나가는 주소에 발송 예산을 쓰지 않으려고 — 11 문서). 리스트 캠페인은 팬아웃 단계에서 이미 걸러져 여기 오지 않습니다.
2. **속도 제한 토큰** — 워크스페이스 버킷에서 1개 차감, 없으면 파킹 큐로(11 문서).
3. **원자적 클레임** — DB에 조건부 UPDATE 한 방으로 "이 메시지는 내가 처리한다"를 선언하고 **claim 토큰**(그때 찍은 `updatedAt`)을 돌려받습니다. 이미 처리됐거나 다른 소비자가 처리 중이면 조용히 리턴.
4. **HTML 조립** — 개인화 변수 치환 → 링크를 클릭 추적 URL로 재작성 → 수신거부 푸터 → 오픈 픽셀 순으로 본문을 쌓고, `List-Unsubscribe` 헤더용 URL 을 함께 넘깁니다.
5. **발송 + 결과 기록** — 성공이면 `SENT`, 실패면 `BOUNCED` + 해당 주소를 억제 목록에 자동 등록. 기록은 blind save 가 아니라 **claim 토큰이 아직 유효할 때만 쓰는 조건부 UPDATE**(`finish`)입니다 — 13 문서 3-1.
6. **completeIfDrained** — 캠페인에 PENDING/SENDING이 하나라도 남았는지 값싼 EXISTS로 확인하고, 안 남았으면 `SENDING → COMPLETED`로만 전환(`completeIfSending`). 팬아웃이 도는 중(`EXPANDING`)인 리스트 캠페인은 조기 완료되지 않습니다.

**억제 목록(suppression list)** 은 "다시는 보내면 안 되는 주소"의 전역 명단입니다. 수신거부 링크 클릭과 발송 실패(반송) 두 경로로 채워지고, 모든 캠페인의 발송 시점에 존중됩니다. 스팸 신고를 피하고 발신자 평판을 지키는, 메일 플랫폼의 필수 장치입니다.

## 2. 흐름

```
SendJob{id} 도착 (MailSendListener → dispatchOne)
      │
      ▼
 claim(id): UPDATE ... WHERE id=? AND (PENDING 이거나 정체된 SENDING)
      │
 클레임 성공? ──아니오──> 리턴 (멱등: 재배달/동시경합 패배 스킵)
      │예 (이제 이 행은 SENDING)
 메시지 조회
      │
 캠페인 조회 ──없음──> markFailed → 저장 → 리턴
      │예
 캠페인 QUEUED→SENDING 전환 (markSendingIfQueued — EXPANDING이면 no-op)
      │
 억제 목록에 있는 주소? ──예──> SUPPRESSED 마킹 ─┐
      │아니오                                    │
 본문 조립: 변수치환 → 링크재작성 → 푸터 → 픽셀   │
      │                                          │
 sender.send(...) ──성공──> SENT ────────────────┤
      │실패                                       │
 BOUNCED 마킹 + 주소를 억제 목록에 추가 ──────────┤
                                                 ▼
                              completeIfDrained: hasPendingOrSending? ──아니오──> completeIfSending (SENDING→COMPLETED만)

[수신거부 경로]  메일 푸터의 링크 클릭
  GET /api/unsubscribe/{token} → SuppressionService → 억제 목록 저장
  → 이후 모든 캠페인에서 이 주소는 SUPPRESSED
```

## 3. 단계별 실제 코드

### 3-1. 원자적 클레임 — 두 번(또는 동시에 두 곳에) 와도 한 번만 보낸다

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
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
```

(2026-09 이전엔 `boolean claim(...)` + `messages.save(message)` 였습니다. 지금은 claim 이 **토큰**을 돌려주고 모든 종료 기록이 그 토큰을 조건으로 겁니다 — 이유는 3-4 와 [13 문서](13-recovery-and-abort.md) 3-1.)

`claim`은 `mail-core/src/main/java/io/github/ahrimjang/mail/core/port/MailMessageRepository.java`에 선언된 포트 메서드고, 실제 구현은 JPA 어댑터의 조건부 UPDATE 한 문장입니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/MailMessageJpaRepository.java`
```java
    @Modifying
    @Transactional
    @Query("update MailMessageEntity m set m.status = io.github.ahrimjang.mail.common.MessageStatus.SENDING, "
            + "m.updatedAt = :now "
            + "where m.id = :id "
            + "and (m.status = io.github.ahrimjang.mail.common.MessageStatus.PENDING "
            + "or (m.status = io.github.ahrimjang.mail.common.MessageStatus.SENDING and m.updatedAt < :staleBefore))")
    int claimPending(@Param("id") Long id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
```

RabbitMQ는 at-least-once라 같은 `SendJob`이 재배달될 수 있고, 재배달이 원래 소비자가 아직 처리 중일 때 도착하면(예: 느린 SMTP 응답 중 ack 타임아웃) **두 소비자가 동시에 이 UPDATE를 쏠 수** 있습니다. 이때 DB가 행 단위로 직렬화해 정확히 하나만 `WHERE`절을 통과시키고(`UPDATE 1`), 나머지는 0건(`UPDATE 0`)으로 진 채 조용히 리턴합니다 — **진실은 큐가 아니라 DB에 있고, 그 DB의 단일 조건부 UPDATE가 경합의 심판**입니다.

`SENDING`으로 정체된 지 `STALE_CLAIM_AFTER`(2분)가 지난 행도 다시 클레임 대상에 포함시키는데, 이건 "소비자가 발송 도중 죽어버린" 경우를 위한 안전장치입니다 — 이게 없으면 크래시 시점에 클레임을 쥔 메시지가 영영 `SENDING`에 갇혀 재시도조차 안 됩니다.

### 3-2. 억제 체크 — 보내기 전에 명단 확인

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
        // 억제 확인은 토큰 소비보다 앞에(ARCH-10). 억제된 주소는 어차피 안 나가는데 발송
        // 토큰을 먼저 쓰면 억제 30% 명단에서 발송 예산 30% 가 허비된다.
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
```

리스트 캠페인은 여기까지 오지 않습니다 — 팬아웃이 페이지 단위로 억제 주소를 일괄 조회해 `SUPPRESSED` 로 바로 기록하고 큐에 넣지 않습니다(02 문서 3-6 변경 노트). 이 경로는 애드혹 캠페인과 과거 큐 잔여분용입니다.

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
        var options = new MailSender.Options(
                campaign.getReplyTo(), unsubscribeUrl(message.getUnsubToken()));
        try {
            sender.send(message.getRecipient(), subject, html, String.valueOf(message.getId()),
                    campaign.getSenderName(), campaign.getSenderEmail(), options);
            message.markSent();
        } catch (Exception e) {
            log.error("send failed: campaign={} recipient={}",
                    campaign.getId(), message.getRecipient(), e);
            message.markBounced(e.getMessage());
            suppressions.save(Suppression.of(campaign.getWorkspaceId(), message.getRecipient(), "bounce"));
        }
        finish(message, claimedAt);
        completeIfDrained(campaign.getId());
```

`senderName`/`senderEmail` 은 캠페인 단위의 **발신자(From) 오버라이드**이고(없으면 `null` → 어댑터 기본 발신자), `Options` 에는 회신 주소와 **원클릭 수신거부 URL**(`List-Unsubscribe`/`List-Unsubscribe-Post` 헤더용, RFC 8058)이 실립니다.

마지막 줄 `finish(message, claimedAt)` 가 2026-09 의 핵심 변경입니다. 예전 `messages.save(message)` 는 **늦게 끝난 쪽이 무조건 이기는** blind save 였습니다 — SMTP 가 2분 넘게 끌려 다른 워커가 stale 재클레임한 뒤에도 첫 워커의 옛 결과가 새 결과를 덮어썼고, 바운스 웹훅이 먼저 쓴 BOUNCED 도 SENT 로 되돌아갔습니다. 지금은 `status = SENDING and updatedAt = claimedAt` 일 때만 기록하고, 0행이면 "내 결과는 이미 무효"라 로그만 남깁니다. 중복 발송 자체는 워커의 SMTP 타임아웃(연결 10초·읽기/쓰기 30초)이 stale 창(2분)보다 짧아진 것으로 막습니다 — [13 문서](13-recovery-and-abort.md) 3-1.

실패하면 `BOUNCED`로 마킹하는 데서 끝나지 않고 **그 주소를 즉시 억제 목록에 넣습니다**("bounce" 사유). 다음 캠페인부터는 시도조차 안 하게 됩니다 — 죽은 주소에 반복 발송하면 발신자 평판이 깎이기 때문입니다. 실패 로그는 `warn`이 아니라 **`error` + 예외 객체(스택트레이스)** 로 남깁니다 — JSON 로그를 통해 OpenSearch 대시보드까지 스택트레이스가 실려 가서, 어떤 발송이 왜 죽었는지 로그 화면에서 바로 역추적할 수 있게 하기 위해서입니다.

### 3-5. 캠페인 완료 판정 — completeIfDrained

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
    private void completeIfDrained(Long campaignId) {
        // Cheap EXISTS instead of a full per-status count on every send: a campaign
        // with any PENDING/SENDING left is still draining. completeIfSending only
        // fires from SENDING, so a campaign mid-EXPANDING is never completed early.
        if (!messages.hasPendingOrSending(campaignId)) {
            campaigns.completeIfSending(campaignId);
        }
    }
```

"내가 마지막 한 건이었나?"를 매 발송마다 물어보되, 예전처럼 상태별 카운트를 세지 않고 **값싼 EXISTS 하나**(`hasPendingOrSending`)로 끝냅니다 — `PENDING`이든 `SENDING`이든 하나라도 걸리면 즉시 short-circuit해서 "아직 드레인 안 됨"을 반환합니다. `SENDING`(다른 소비자가 지금 막 클레임해 처리 중인 행)까지 봐야 하는 이유는 그대로입니다: 그게 남아 있으면, 같은 캠페인의 메시지 여러 건이 동시에 처리되는 도중 마지막 PENDING이 방금 SENDING으로 넘어간 그 찰나에 다른 메시지의 `completeIfDrained`가 "PENDING 0"만 보고 캠페인을 조기에 `COMPLETED`로 확정해버리는 착시가 생깁니다.

완료는 `completeIfSending`으로 **`SENDING → COMPLETED`만** 승인합니다. 덕분에 팬아웃이 도는 중(`EXPANDING`)인 리스트 캠페인은 — 앞 배치가 다 나갔어도 뒤 배치의 메시지가 아직 만들어지지 않았을 그 순간에도 — 절대 조기 완료되지 않습니다(팬아웃이 확장을 마치고 `SENDING`으로 넘긴 뒤에야 완료 가능; [02 문서 3-6](02-campaign-queue-rabbitmq.md#3-6-팬아웃-확장--리스트-캠페인을-워커가-비동기로-펼친다) 참고).

참고로 첫 진행 시의 `markSending`도 조건부입니다 — `markSendingIfQueued`는 `QUEUED → SENDING`만 수행하므로, 이미 `EXPANDING`인 리스트 캠페인에는 no-op입니다(그 캠페인의 `EXPANDING → SENDING` 전환은 팬아웃이 소유). 애드혹 캠페인은 `QUEUED`에서 바로 시작하니 첫 발송이 `SENDING`으로 올립니다.

이 EXISTS 한 방은 예전의 `countByCampaign`(상태별 7×COUNT)을 대체한 것입니다. 발송 1건마다 7개 COUNT를 세면 캠페인이 커질수록 발송 총비용이 O(N²)로 불어납니다. 드레인 체크와 대시보드 카운트 모두 `(campaign_id, status)` 복합 인덱스(V7 `idx_msg_campaign_status`)가 받쳐주고, EXISTS는 그 인덱스에서 첫 매치만 찾고 멈춥니다.

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

실제 발송은 `MailSender` 포트 뒤의 어댑터가 합니다. 포트 시그니처에 발신자 오버라이드 두 인자가 포함되어 있고, `null`이면 어댑터 기본값으로 폴백한다는 계약이 명시되어 있습니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/MailSender.java`
```java
    /**
     * Send one mail.
     *
     * @param senderName  From display name; null falls back to the adapter default
     * @param senderEmail From address; null falls back to the adapter default
     * @throws MailSendException if delivery fails (the worker records it as FAILED)
     */
    void send(String recipient, String subject, String body, String messageId,
              String senderName, String senderEmail) throws MailSendException;
```

worker는 `mail.sender.type=smtp`라 SMTP 구현이 뜹니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/mail/SmtpMailSender.java`
```java
@Component
@ConditionalOnProperty(name = "mail.sender.type", havingValue = "smtp")
public class SmtpMailSender implements MailSender {
```
```java
    @Override
    public void send(String recipient, String subject, String body, String messageId,
                     String senderName, String senderEmail) throws MailSendException {
        if (recipient == null || !recipient.contains("@")) {
            throw new MailSendException("invalid recipient address: " + recipient);
        }
        MimeMessage msg = mailSender.createMimeMessage();
        try {
            MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
            h.setTo(recipient);
            h.setSubject(subject);
            h.setText(body, true);
            // Campaign-level From override; without it the SMTP session default applies.
            if (senderEmail != null && !senderEmail.isBlank()) {
                if (senderName != null && !senderName.isBlank()) {
                    h.setFrom(senderEmail, senderName);
                } else {
                    h.setFrom(senderEmail);
                }
            }
            if (messageId != null) {
                msg.setHeader("X-Mail-Message-Id", messageId);
            }
            mailSender.send(msg);
        } catch (Exception e) {
            throw new MailSendException("failed to send to " + recipient + ": " + e.getMessage(), e);
        }
```

`setText(body, true)`의 `true`가 "HTML로 보내라"는 뜻입니다. `senderEmail`이 있으면 `setFrom`으로 From 헤더를 캠페인 지정 값으로 바꾸고(`senderName`이 있으면 표시 이름까지), 없으면 `setFrom`을 아예 호출하지 않아 SMTP 세션 기본 발신자가 그대로 적용됩니다. `X-Mail-Message-Id` 헤더에 우리 메시지 id를 실어 보내는데, 반송 웹훅이 어떤 메시지의 반송인지 역추적할 때 씁니다.

api/admin 프로세스는 발송할 일이 없으므로 기본값인 로깅 구현이 뜹니다(속성 없으면 `matchIfMissing = true`).

`infra/src/main/java/io/github/ahrimjang/mail/infra/mail/LoggingMailSender.java`
```java
@Component
@ConditionalOnProperty(name = "mail.sender.type", havingValue = "logging", matchIfMissing = true)
public class LoggingMailSender implements MailSender {
```
```java
    @Override
    public void send(String recipient, String subject, String body, String messageId,
                     String senderName, String senderEmail) throws MailSendException {
        if (recipient == null || !recipient.contains("@")) {
            throw new MailSendException("invalid recipient address: " + recipient);
        }
        log.info("[MAIL] -> {} | from={} <{}> | subject=\"{}\" | bodyChars={} | messageId={}",
                recipient,
                senderName == null ? "(default)" : senderName,
                senderEmail == null ? "default" : senderEmail,
                subject, body == null ? 0 : body.length(), messageId);
    }
```

두 구현 모두 `@`가 없는 주소를 일부러 예외로 던집니다 — BOUNCED 경로를 손쉽게 시험해 보기 위한 장치입니다.

## 4. 설계 포인트 (왜 이렇게)

- **멱등 소비자 + 원자적 클레임**: at-least-once 큐 앞에서는 "중복이 와도 안전한 소비자"가 정답입니다. 처음엔 상태 체크(`!= PENDING → 스킵`) 하나로 해결했지만, **조회와 저장 사이에 락이 없어** 워커 여러 대(또는 재배달)가 동시에 같은 id를 읽으면 둘 다 PENDING을 보고 둘 다 발송해버리는 이중 발송 창이 있었습니다. 지금은 `claim()`이 단일 조건부 UPDATE(`WHERE id=? AND status='PENDING'`)로 그 read-then-write 사이의 창을 없앱니다 — DB가 행 단위로 동시 UPDATE를 직렬화해주므로 정확히 하나만 이깁니다.
  - `SELECT ... FOR UPDATE SKIP LOCKED`가 아니라 **단일 조건부 UPDATE**를 택한 이유: `FOR UPDATE SKIP LOCKED`는 "대기 중인 여러 후보 중 아직 안 잠긴 N개를 골라온다"(배치 폴링/클레임)에 어울리는 기법인데, RabbitMQ가 이미 처리할 `messageId`를 콕 집어 넘겨주는 지금 구조엔 안 맞습니다. 조건부 UPDATE는 같은 원자성 보장을 SQL 한 문장으로 주면서, 느린 SMTP 호출 동안 DB 락/커넥션을 붙들고 있을 필요도 없습니다.
  - **claim 은 반쪽이었습니다**: 클레임에 `SENDING` 을 도입하면서 "이긴 뒤 죽으면"의 문제가 생겼습니다. stale 재클레임(2분)은 재전달이 있을 때만 돌고, 재전달 없이 잡이 사라진 행(발행 직후 크래시, DLQ 행)은 영영 SENDING 이었습니다. 2026-09 에 셋으로 닫았습니다 — 종료 기록의 **claim 토큰 조건부 UPDATE**(덮어쓰기 방지), **SMTP 타임아웃**(stale 창 안에 끝나게 → 중복 발송 방지), **복구 스위퍼 + DLQ 리스너**(잡이 사라진 행의 재발행/확정). 자세한 것은 [13 문서](13-recovery-and-abort.md).
- **완료 판정은 값싼 EXISTS로, 발송은 병렬로**: 예전엔 발송 1건이 끝날 때마다 상태별 7×COUNT(`countByCampaign`)로 드레인을 확인해, 캠페인이 커질수록 발송 총비용이 O(N²)로 불어났습니다. 지금은 첫 매치에서 멈추는 short-circuit EXISTS(`hasPendingOrSending`)로 발송당 비용을 일정하게 유지하고, `(campaign_id, status)` 복합 인덱스(V7)가 이 체크와 대시보드 카운트를 함께 받칩니다. 완료는 `completeIfSending`으로 `SENDING`에서만 승인하므로 `EXPANDING` 캠페인의 조기 완료도 막습니다. 발송 경로가 I/O 바운드(DB 왕복 + SMTP)라 워커 리스너 동시성도 1→8(`spring.rabbitmq.listener.simple.concurrency`, env로 조정)로 올려 워커 한 대가 여러 건을 병렬 발송하며, 여러 소비자가 완료 판정을 동시에 쳐도 SENDING-only 게이트가 조기 완료를 막습니다.
- **메시지 상태는 "전달 결과"만**: `PENDING → SENDING → SENT | FAILED | BOUNCED | SUPPRESSED | CANCELED`(취소·중단). 열람/클릭은 상태가 아니라 별도 `EmailEvent`로 쌓습니다. 한 메시지가 "보내졌고 + 열렸고 + 클릭됐다"는 다차원 사실을 단일 상태로 욱여넣지 않기 위해서입니다.
- **억제는 워크스페이스 단위, 다섯 경로로 유입**: 수신거부 링크·원클릭 헤더(`unsubscribe`), 발송 시점 실패(`bounce`), SES 웹훅의 하드 바운스(`hard_bounce`)·스팸 신고(`complaint`), 운영자 수동(`manual`). 전부 같은 `suppressions` 테이블(`(workspace_id, email)` 유니크)로 가고 `reason` 으로 구분됩니다. 콘솔의 수신자 → 억제 목록 탭이 이 테이블을 그대로 보여줍니다.
- **발신자(From)는 캠페인의 속성, 폴백은 어댑터의 몫**: `senderName`/`senderEmail`은 캠페인 행에 저장돼 발송 시 포트로 그대로 전달되고, `null` 처리(기본 발신자 폴백)는 각 어댑터가 책임집니다. core는 "오버라이드가 있으면 넘긴다"만 알면 되고, 기본 발신자가 무엇인지(SMTP 세션 설정)는 infra 관심사로 남습니다.
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

체크 포인트: ① `broken` 주소가 `bounced`로 집계되는지, ② 반송/수신거부된 주소가 **다음 캠페인에서 자동으로 `suppressed`** 되는지, ③ MailHog에서 메일 본문 맨 아래에 수신거부 푸터와 (소스 보기 시) 오픈 픽셀 `<img>` 태그가 붙어 있는지. worker 콘솔 로그에서도 `send failed: ...` 에러 로그(스택트레이스 포함)를 확인할 수 있습니다.

### 5-1. 클레임 경합 직접 증명

`claim()`이 실제로 동시 호출 중 하나만 이기는지는, PENDING인 메시지 행 하나에 **똑같은 UPDATE를 정말로 동시에** 두 번 쏴서 확인할 수 있습니다.

```bash
MID=<PENDING 상태인 mail_messages.id>
psql -U maildb -d maildb -c "
update mail_messages set status='SENDING', updated_at=now()
where id=$MID and (status='PENDING' or (status='SENDING' and updated_at < now() - interval '2 minutes'));" &
psql -U maildb -d maildb -c "
update mail_messages set status='SENDING', updated_at=now()
where id=$MID and (status='PENDING' or (status='SENDING' and updated_at < now() - interval '2 minutes'));" &
wait
# 기대 결과: 한쪽은 "UPDATE 1", 다른 쪽은 "UPDATE 0"
```
