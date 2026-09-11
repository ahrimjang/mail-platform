# 13. 고착 복구와 발송 중단 — "claim 에 이긴 뒤"를 설계한다

## 1. 개요 (무엇을, 왜)

02·03·09 문서의 동시성은 전부 **원자적 조건부 UPDATE claim** 하나로 풉니다 — 발송 claim
(PENDING→SENDING), 팬아웃 claim(QUEUED→EXPANDING), 예약 릴리스, A/B 승자. 이 패턴은
"둘이 동시에 시작하는 것"을 완벽히 막습니다. 그런데 2026-08 감사가 짚은 것은 그 **뒤**였습니다:

- claim 에 이긴 워커가 **도중에 죽으면** 누가 이어받나? 재전달된 잡은 claim 에서 져서 정상
  return 하므로 DLQ 도 가지 않습니다 — 조용한 영구 고착(ARCH-1·2·5).
- 재시도 3회를 소진해 **DLQ 로 빠진 잡**은 누가 보나? 소비자가 없었고, 그 메시지는 SENDING
  인 채 남아 캠페인이 영원히 "발송 중"이었습니다(ARCH-3).
- 종료 상태(SENT/FAILED…)를 **blind save** 로 쓰면, 늦게 끝난 쪽이 무조건 이깁니다. SMTP 가
  오래 걸려 다른 워커가 stale 재클레임한 뒤에도 첫 워커의 옛 결과가 새 결과를 덮어쓰고,
  바운스 웹훅이 먼저 쓴 BOUNCED 도 SENT 로 되돌아갔습니다(ARCH-4).
- 오발송을 알아채도 **멈출 코드가 없었습니다** — 예약 취소뿐(ARCH-8).

이 문서는 2026-09-10~11 에 들어간 그 답들을 따라갑니다. 한 줄로 줄이면 **claim 은 반쪽이고,
이긴 뒤의 복구·기록·확정 근거·중단까지 같이 설계해야 한 세트**라는 것입니다.

## 2. 흐름

```
                     ┌──────────────── 정상 경로 ────────────────┐
 잡 ──▶ claim(토큰=updatedAt) ──▶ 발송 ──▶ finish(status, 토큰 일치할 때만) ──▶ completeIfDrained
                     └────────────────────────────────────────────┘
   │                                │
   │ 3회 실패                        │ 워커 사망 (claim 은 이겼는데 finish 못 함)
   ▼                                ▼
 mail.send.dlq ──▶ DeadLetterListener        RecoverySweeper (60초)
   FAILED 확정(PENDING/SENDING 만)             ├─ EXPANDING 10분↑ → QUEUED 되돌려 팬아웃 재발행(커서 재개)
   completeIfDrained · 인앱 알림 1회/캠페인     ├─ 릴리스됐는데 QUEUED 10분↑ → 팬아웃 재발행 / 메시지 없으면 CANCELED
   지표 mail.dlq.received                      └─ PENDING/SENDING 10분↑ → 재발행(claim 이 멱등하게 받음)
                                                  지표 mail.recovery

 사람: 캠페인 상세 "발송 중단" ──▶ POST /api/campaigns/{id}/abort
   QUEUED/EXPANDING/SENDING → CANCELED (조건부, 이긴 쪽만) ──▶ PENDING 일괄 취소
   팬아웃은 페이지마다 상태 확인 후 멈춤 · 디스패치는 취소된 잡을 종료 상태라 건너뜀
```

## 3. 단계별 실제 코드

### 3-1. 종료 기록은 claim 토큰이 붙은 조건부 UPDATE (ARCH-4)

`claim` 이 찍은 `updatedAt` 을 돌려받아 토큰으로 씁니다. 종료 기록은 그 토큰이 아직 행에
그대로일 때만 — 누가 사이에 손댔으면(재클레임·바운스 선반영) 0행이고, 그건 "내 결과는
이미 무효"라는 뜻입니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
        java.util.Optional<java.time.Instant> claimed = messages.claim(messageId, STALE_CLAIM_AFTER);
        if (claimed.isEmpty()) {
            return;
        }
        java.time.Instant claimedAt = claimed.get();
        ...
        finish(message, claimedAt);
        completeIfDrained(campaign.getId());
    }

    private void finish(MailMessage message, java.time.Instant claimedAt) {
        boolean recorded = messages.finish(message.getId(), claimedAt, message.getStatus(),
                message.getErrorMessage(), message.getUpdatedAt());
        if (!recorded) {
            log.warn("종료 기록 건너뜀 — claim 토큰 불일치(재클레임 또는 바운스 선반영): message={} status={}",
                    message.getId(), message.getStatus());
        }
    }
```

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/MailMessageJpaRepository.java`
```java
    @Query("update MailMessageEntity m set m.status = :status, m.errorMessage = :error, "
            + "m.updatedAt = :now, m.attempts = m.attempts + 1 "
            + "where m.id = :id and m.status = io.github.ahrimjang.mail.common.MessageStatus.SENDING "
            + "and m.updatedAt = :claimedAt")
    int finish(...);
```

`updatedAt = :claimedAt` 의 `=` 비교가 정확히 맞아야 하므로 어댑터는 Instant 를 **마이크로초로
잘라** 쓰고 돌려줍니다(`timestamptz(6)`). 바운스 웹훅도 같은 원칙으로 `markBouncedIfDelivered`
— SENT/SENDING 에서만 BOUNCED 로, 이미 BOUNCED 면 0행이라 이벤트도 두 번 안 나갑니다.

**덮어쓰기 방지와 중복 발송 방지는 다른 문제**입니다. 조건부 UPDATE 는 전자만 풉니다. 후자의
근원은 타임아웃 없는 SMTP 였습니다 — 응답이 2분 넘게 끌리면 stale 창을 넘겨 다른 워커가
재클레임해 **두 번째 발송**을 합니다. 워커 `application.yml` 에 연결 10초·읽기/쓰기 30초를
걸어 어떤 hang 도 stale 창 안에 끝나게 했습니다. 이제 재클레임은 "정말 죽은 워커"에만 걸립니다.

### 3-2. DLQ 소비자 (ARCH-3)

`mail-worker/src/main/java/io/github/ahrimjang/mail/worker/DeadLetterListener.java`
```java
    @RabbitListener(queues = RabbitMailConfig.DLQ)
    public void onDeadLetter(Message message) {
        Envelope env;
        try {
            env = Envelope.parse(message, mapper);          // __TypeId__ · x-death · 본문으로 send/fanout 판별
        } catch (Exception e) {
            Metrics.counter("mail.dlq.received", "type", "unparseable").increment();
            log.error("DLQ 메시지를 해석하지 못했다 — 소비하고 넘어간다. ...", e);
            return;
        }
        Metrics.counter("mail.dlq.received", "type", env.type()).increment();
        try {
            switch (env.type()) {
                case "send" -> deadLetters.sendJobDead(env.id(), env.reason());
                case "fanout" -> deadLetters.fanoutJobDead(env.id(), env.reason());
                default -> log.error("알 수 없는 DLQ 메시지 타입 — 무시한다: {}", env);
            }
        } catch (Exception e) {
            log.error("DLQ 뒷정리 실패 — 메시지는 소비한다: {}", env, e);
        }
    }
```

두 가지가 중요합니다. ① 원시 `Message` 로 받습니다 — 타입 변환 실패 자체가 DLQ 행 사유일 수
있어서 컨버터에 맡기지 않습니다. ② **절대 예외를 밖으로 내지 않습니다** — DLQ 에는 데드레터
목적지가 없어 여기서 거부되면 메시지가 사라집니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/DeadLetterService.java`
```java
    public void sendJobDead(Long messageId, String reason) {
        MailMessage message = messages.findById(messageId).orElse(null);
        if (message == null) { ...; return; }
        String error = "발송 처리가 반복 실패해 중단됐어요 (" + reason + ")";
        if (!messages.finishIfActive(messageId, MessageStatus.FAILED, error, Instant.now())) {
            return;                                   // 이미 종료된 행 — 성공을 실패로 덮지 않는다
        }
        dispatch.completeIfDrained(message.getCampaignId());   // 마지막 메시지였다면 캠페인을 마무리
        notifyOnce(message.getCampaignId(), notifications::campaignSendFailed);
    }
```

팬아웃 잡이 DLQ 로 오면 알림만 합니다 — 커서 재개가 필요한 상태 복구는 3-3 의 스위퍼 몫입니다.
알림은 캠페인당 1시간에 1회 — 포이즌 메시지 1,000건이 알림 1,000건이 되면 안 됩니다.

### 3-3. 복구 스위퍼 (ARCH-1·2·5)

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/RecoveryService.java`
```java
    public Sweep sweep() {
        Instant cutoff = now.minus(Duration.ofMinutes(STUCK_MINUTES));   // 10분

        // 1) 팬아웃 도중 죽은 캠페인 — 되돌리기에 이긴 호출만 재발행한다
        for (Campaign c : campaigns.findStuckExpanding(cutoff)) {
            if (campaigns.resetExpandingToQueued(c.getId(), cutoff)) {
                mailQueue.enqueueFanout(c.getId());
            }
        }
        // 2) 릴리스됐는데 잡이 없는 QUEUED
        for (Campaign c : campaigns.findOrphanQueued(cutoff)) {
            if (c.getListId() != null) {
                mailQueue.enqueueFanout(c.getId());               // 진행 중이었다면 claim 에서 져 무해
            } else if (messages.findPendingIdsByCampaign(c.getId()).isEmpty()
                    && !messages.hasPendingOrSending(c.getId())) {
                campaigns.updateStatus(c.getId(), CampaignStatus.CANCELED);   // create() 실패 잔존물
            }
        }
        // 3) 오래도록 PENDING/SENDING 인 메시지 재발행
        List<Long> stale = messages.findStaleIds(cutoff, STALE_BATCH);   // 200건
        messages.touchPending(stale, now);                                // 다음 스위프에서 또 안 잡히게
        stale.forEach(mailQueue::enqueue);
        ...
    }
```

세 패스의 공통 원칙: **스위퍼는 상태를 새로 만들지 않고 재발행만 합니다.** 오탐이어도 재발행은
소비 쪽 claim 이 멱등하게 받아내므로 발송 결과가 바뀌지 않습니다. 판정 기준 "10분"은 정상
처리에 그만큼 걸리는 일이 없다는 데서 왔습니다.

- **EXPANDING 고착**은 V34 `expanding_started_at`(팬아웃 claim 이 찍음)으로 판정합니다.
  되돌린 뒤 팬아웃이 다시 돌 때 **이미 만든 메시지의 최대 contactId 뒤부터 재개**합니다 —
  연락처를 id 오름차순으로 페이지 넘기므로 그 값이 곧 마지막 처리 위치입니다. 0 부터 다시 돌면
  같은 수신자에게 두 번 갑니다.
- **홀드아웃(ARCH-2)** 은 따로 처리하지 않습니다. 승자가 확정된 캠페인의 variant-null PENDING
  은 3번 패스의 조건(`not (variant is null and abTestPercent is not null and abWinner is null)`)
  을 통과하므로 자연히 재발행됩니다. 승자 미정 홀드아웃만 정상 대기라 제외됩니다.
- **비용 상한**: 속도 제한에 오래 파킹된 PENDING 도 3번에 걸릴 수 있습니다(구분할 방법이 없음).
  중복 잡은 throttle 경로로 다시 파킹될 뿐이고, 배치 200건 + `touchPending` 이 증폭을 막습니다.

`create()` 쪽도 고쳤습니다 — 대상 검증(빈 리스트·빈 수신자)을 저장보다 **앞**으로. 스위퍼가
걷어내긴 하지만 고아를 애초에 만들지 않는 게 먼저입니다.

### 3-4. 발송 중 중단 (ARCH-8)

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignService.java`
```java
    public CampaignView abort(Long id) {
        campaigns.findById(id).filter(this::owned).orElseThrow(...);
        if (!campaigns.abort(id)) {                      // QUEUED/EXPANDING/SENDING → CANCELED 조건부
            throw new IllegalStateException("이미 끝났거나 취소된 캠페인이라 중단할 수 없어요: " + id);
        }
        int canceled = messages.cancelPendingByCampaign(id);
        return get(id);
    }
```

순서가 중요합니다: 캠페인 전이에 **이긴 다음** PENDING 을 일괄 취소합니다. 그 뒤 팬아웃 루프는
페이지마다 상태를 보고 멈추고(`CampaignFanoutService.expand` 의 루프 첫 줄), 디스패치는 취소된
메시지 잡을 종료 상태라 건너뜁니다. 이미 claim 돼 SMTP 로 넘어간 몇 통(워커 동시성 ≤16)은
회수할 수 없습니다 — 화면·가이드·운영 매뉴얼에 그렇게 적혀 있습니다. 새 상태(ABORTING)를
만들지 않고 CANCELED 를 재사용했습니다 — enum 값 추가는 V1 CHECK 재생성이 따라붙는데,
"남은 발송이 취소된 캠페인"이라는 뜻은 기존 값으로 충분합니다.

## 4. 같은 시기에 닫은 것들 (다른 문서의 변경 노트)

| 항목 | 요지 | 자세히 |
|---|---|---|
| 월 한도 예산(ARCH-9) | `sent + 이번 대상 > limit` 이면 등록 거절 — 등록이 유일한 집행 지점 | [10](10-multitenancy.md) |
| A/B 근거 요건(ARCH-6) | 배치 미완료·안별 10통 미만·반응 0·동률이면 10분 유예, 24h 뒤 확정 | [09](09-ab-testing.md) |
| A/B SHA 분배(ARCH-7) | `hashCode` → SHA-256 버킷, 등록 시 테스트군 안별 10명 미만이면 승자 플로우 거절 | [09](09-ab-testing.md) |
| 억제 팬아웃 필터(ARCH-10) | 팬아웃이 억제 주소를 SUPPRESSED 로 바로 기록·큐 미발행, 디스패치는 억제 확인을 토큰 앞으로 | [03](03-dispatch-suppression.md) · [11](11-send-throttling.md) |
| 참여도 창(ARCH-11) | 집계를 워크스페이스·최근 180일로 한정 | [02 §3-6b](02-campaign-queue-rabbitmq.md) |

## 5. 확인 방법

정상 운영에서는 아무것도 안 보이는 게 맞습니다. 보이면 조사 신호입니다.

- **지표**(Grafana, worker `/actuator/prometheus`): `mail_dlq_received_total{type}` 과
  `mail_recovery_total{kind}` — 0 이 아닌 값이 **반복**되면 무언가 계속 죽고 있다는 뜻.
- **워커 로그**: `DLQ:` ERROR, `복구:` WARN, `종료 기록 건너뜀` WARN(재클레임·바운스 선반영 —
  드물게 정상), `fan-out ... resumes after contactId` WARN(스위퍼가 되돌린 팬아웃의 재개).
- **RabbitMQ UI**: `mail.send.dlq` 깊이는 리스너가 소비하므로 평소 0.
- **로컬 재현**: 워커 하나로 대량 리스트 캠페인을 팬아웃하다 `kill -9` → 캠페인이 EXPANDING
  에 남는다 → 워커 재기동 후 ~10분 안에 `복구: EXPANDING 고착 캠페인 N 을 QUEUED 로 되돌리고
  팬아웃 재발행` → `resumes after contactId` → COMPLETED. `select count(*), count(distinct
  recipient) from mail_messages where campaign_id=?` 가 같아야 합니다(중복 없음).

## 6. 관련 문서
- [../worklog/2026-09-11.md](../worklog/2026-09-11.md) — 이 변경들의 배경과 배운 것
- [../OPS-MANUAL.md](../OPS-MANUAL.md) 2절·5절 — 경보·DLQ·스위퍼·중단 운영 절차
