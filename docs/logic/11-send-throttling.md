# 11. 테넌트별 발송 속도 제한 — Postgres 토큰버킷 + TTL 파킹 큐

## 1. 개요 — 무엇을, 왜

멀티테넌트 전환(10 문서)이 만든 새 문제 두 가지를 한 장치로 풉니다.

- **Noisy neighbor**: 모든 테넌트가 발송 큐 하나를 공유하므로, A사가 100만 건 캠페인을
  걸면 B사의 10건이 그 뒤에 줄을 섭니다. 큐를 테넌트별로 쪼개는 대신, **한도를 넘긴
  테넌트의 메시지만 잠시 옆으로 비켜서게** 합니다.
- **프로바이더 한도**: 실제 릴레이(SES 등)는 초당 발송 한도가 있습니다. 플랫폼 SES의
  총 한도를 지키려면 테넌트별 속도부터 다스릴 수 있어야 하고, 회사 입장에서도 대량
  캠페인을 고르게 나눠 보내고 싶은 수요가 있어 **관리 페이지에서 속도를 설정**합니다
  (`send_rate_per_sec`, 비우면 무제한).

방식은 고전적인 **토큰버킷**입니다: 워크스페이스마다 버킷이 있고 초당 `rate`개의 토큰이
차오르며(최대 `rate`개 = 1초 버스트), 메시지 1건 발송에 토큰 1개를 씁니다. 토큰이 없으면
그 메시지는 실패가 아니라 **1초짜리 파킹 큐에 들렀다가 다시 발송 큐로** 돌아옵니다.

두 가지 상태가 필요하고, 각각 이미 쓰던 인프라에 얹었습니다 — 새 인프라(Redis) 없이:

| 상태 | 어디에 | 왜 |
|------|--------|-----|
| 버킷(토큰 잔량·마지막 리필 시각) | Postgres `workspace_send_buckets` | 워커 N대가 **하나의 버킷**을 공유해야 함 — 원자적 조건부 UPDATE claim 패턴 재사용 |
| 대기 중인 메시지 | RabbitMQ `mail.send.throttled` (TTL 1s) | 재시도를 "큐가 기억"하므로 워커는 잠들지도, 스핀하지도 않음 |

## 2. 흐름

```
[worker] MailSendListener ──▶ dispatchOne(messageId)
    │
    ├─ 메시지 조회, 이미 끝난 상태(SENT/FAILED/…)면 skip   ← 재배달이 토큰을 낭비하지 않게
    │
    ├─ rateLimiter.tryAcquire(campaign.workspaceId)
    │        │
    │        ├─ 무제한(rate null) ──────────────▶ 통과 (캐시 덕에 추가 쿼리 0)
    │        ├─ 토큰 있음: 원자적 UPDATE로 1개 차감 ─▶ 통과
    │        └─ 토큰 없음 ──▶ queue.enqueueThrottled(messageId) 후 종료
    │                              │
    │                              ▼
    │                    mail.send.throttled  (소비자 없음, x-message-ttl=1000)
    │                              │ 1초 뒤 만료 → dead-letter
    │                              ▼
    │                    mail.send.queue 꼬리로 재진입 ──▶ 다시 dispatchOne
    │
    ├─ messages.claim(...)  ← 토큰 확인은 반드시 claim "앞"에서 (설계 포인트 참고)
    └─ (이하 03 문서의 발송 경로 그대로: 억제 확인 → 렌더 → SMTP → SENT)
```

핵심 성질: 파킹 큐로 빠지는 건 **한도를 넘긴 테넌트의 메시지뿐**입니다. 그 사이 다른
테넌트의 메시지는 발송 큐를 그대로 통과하므로 noisy neighbor가 사라집니다(5절 실측).

## 3. 단계별 실제 코드

### 3-1. 스키마 (V19)

`infra/src/main/resources/db/migration/V19__workspace_send_rate.sql`
```sql
alter table workspaces add column send_rate_per_sec integer
    constraint chk_workspaces_send_rate check (send_rate_per_sec > 0);

create table workspace_send_buckets (
    workspace_id bigint primary key references workspaces (id),
    tokens       numeric(12,4) not null,
    refilled_at  timestamptz(6) not null
);
```

설정(`send_rate_per_sec`)과 버킷 상태를 **다른 테이블**에 둔 이유: 버킷은 메시지마다
UPDATE되는 핫패스 행이라, `workspaces` 행에 두면 설정 조회와 잠금이 얽힙니다. 버킷 행은
첫 acquire 때 lazy 생성됩니다.

### 3-2. 포트 — 코어가 아는 전부

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/SendRateLimiter.java`
```java
public interface SendRateLimiter {
    /** true = 지금 보내도 됨(무제한 포함). false = 한도 초과, 나중에 다시. */
    boolean tryAcquire(long workspaceId);
}
```

### 3-3. 어댑터 — 리필과 차감을 UPDATE 한 문장으로

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/JdbcSendRateLimiter.java`
```java
int taken = jdbc.update("""
        update workspace_send_buckets
        set tokens = least(cast(? as numeric),
                           tokens + extract(epoch from (clock_timestamp() - refilled_at)) * ?) - 1,
            refilled_at = clock_timestamp()
        where workspace_id = ?
          and tokens + extract(epoch from (clock_timestamp() - refilled_at)) * ? >= 1
        """, rate, rate, workspaceId, rate);
return taken == 1;
```

- "지난 시간 × rate 만큼 리필(상한 rate) → 1개 차감"이 **원자적 한 문장**입니다.
  워커 여럿이 동시에 때려도 Postgres가 행 잠금으로 직렬화하므로 초과 인출이 없습니다 —
  발송 claim(03)·팬아웃 claim(02)과 같은 "원자적 조건부 UPDATE" 패턴의 세 번째 적용처.
- WHERE에는 상한(`least`)이 필요 없습니다: rate ≥ 1(CHECK)이면 "상한 적용 후 ≥ 1"과
  "적용 전 ≥ 1"이 동치이기 때문.
- 워크스페이스의 rate 자체는 3초 캐시합니다. **무제한 테넌트는 첫 조회 이후 메시지당
  추가 쿼리 0**, 콘솔에서 바꾼 rate는 3초 안에 반영됩니다.

여기엔 실측이 잡아준 함정이 하나 있습니다: rate 조회를
`jdbc.query(...).stream().findFirst()`로 썼다가, 무제한 워크스페이스(rate NULL)의 결과가
null 요소가 되면서 `Optional.of`가 NPE를 던졌습니다(`findFirst`는 null 요소를 허용하지
않음). 단위 테스트는 rate 있는 경로만 커버해서 통과했고, **E2E 실측에서 무제한 테넌트의
메시지가 전부 DLQ로 빠지는 걸 보고서야** 발견 — 리스트 인덱싱으로 교체했습니다.

### 3-4. 디스패치 통합 — 토큰 확인은 claim보다 먼저

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
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
    // Tenant throttle, checked BEFORE the claim: a throttled message must stay
    // PENDING so its delayed redelivery claims it normally — claiming first
    // would strand it in SENDING until the stale-claim window expires.
    if (campaign != null && !rateLimiter.tryAcquire(campaign.getWorkspaceId())) {
        queue.enqueueThrottled(messageId);
        return;
    }
    if (!messages.claim(messageId, STALE_CLAIM_AFTER)) { ... }
```

순서가 곧 정합성입니다. claim(PENDING→SENDING)을 먼저 하고 나서 토큰이 없다는 걸 알면,
그 메시지는 SENDING에 갇혀 **stale 재클레임 창(2분)이 지나야** 되살아납니다. 토큰 확인을
앞에 두면 거절된 메시지는 PENDING 그대로라, 1초 뒤 재배달이 평범하게 claim해 갑니다.

### 3-5. 파킹 큐 — 소비자 없는 큐 + TTL + dead-letter

`infra/src/main/java/io/github/ahrimjang/mail/infra/messaging/RabbitMailConfig.java`
```java
@Bean
public Queue throttleQueue() {
    return QueueBuilder.durable(THROTTLE_QUEUE)
            .withArgument("x-message-ttl", THROTTLE_DELAY_MS)      // 1000ms
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            .withArgument("x-dead-letter-routing-key", ROUTING_KEY) // → mail.send.queue
            .build();
}
```

`mail.send.throttled`에는 리스너가 없습니다. 메시지는 1초 유통기한을 기다렸다가
dead-letter 규칙에 따라 발송 큐 **꼬리**로 되돌아갑니다 — 그 사이에 도착한 다른 테넌트의
메시지들이 자연스럽게 먼저 소비되는, 공정성이 공짜로 따라오는 구조입니다.

두 가지 대안을 기각한 이유:
- **nack + requeue**: 큐 머리로 돌아가 즉시 재배달 → 같은 메시지가 초당 수백 번 도는 hot loop.
- **리스너에서 sleep 후 재시도**: 그 1초 동안 소비자 슬롯 하나가 통째로 잠김 — 동시성
  8이면 한도 초과 테넌트가 슬롯 8개를 다 재우고 noisy neighbor가 그대로 재현됩니다.

주의: 큐 인자(TTL 등)는 선언 후 변경 불가입니다(다른 인자로 재선언하면 RabbitMQ가
거부). 지연을 바꾸려면 큐를 지우고 재선언해야 하므로 상수로 못박아 두었습니다.

### 3-6. 설정 노출 — 관리 페이지

`UpdateWorkspaceRequest`/`WorkspaceView`에 `sendRatePerSec`(null=무제한)를 태우고
(`types.ts` 미러), 관리 페이지의 "발송 설정" 카드에 입력을 붙였습니다. 검증은
`WorkspaceService.update`에서 null 또는 ≥1. (처음엔 BYO 카드에 있었으나 V20 과금 모델
전환으로 BYO가 폐기되며 발송 설정 카드로 독립 — 기능 자체는 무변경.)

## 4. 설계 포인트 — 왜 이렇게

- **Redis가 아니라 Postgres인 이유.** ROADMAP 초안은 "공유 상태는 Redis"였지만, 이
  저장소의 동시성 불변식은 전부 "원자적 조건부 UPDATE"로 풀려 있습니다(발송 claim, 팬아웃
  claim, 예약 릴리스/취소, A/B 승자, 완료 판정). 같은 패턴이면 새 인프라 없이 워커 N대
  정합성이 보장되고, 운영·데모·장애 지점이 하나 줄어듭니다. 비용은 스로틀된 테넌트의
  메시지당 UPDATE 1회 — 이게 병목이 되는 규모가 오면 `SendRateLimiter` 포트 뒤에 Redis
  구현을 꽂으면 되고, 코어는 무변경입니다(헥사고날의 교체 지점).
- **버스트 = rate(1초 치)로 고정.** 버킷 용량을 별도 설정으로 빼면 개념이 둘로 늘지만,
  "1초 치까지 몰아 보낼 수 있다"는 단일 규칙은 설명이 한 문장으로 끝납니다. 필요해지면
  컬럼 하나 추가로 확장 가능한 결정.
- **끝난 메시지의 재배달은 토큰을 안 씁니다.** at-least-once 큐에서 재배달은 일상이라,
  상태 가드 없이 tryAcquire부터 하면 유령 재배달이 실제 발송 예산을 깎아 먹습니다.
- **트랜잭셔널/캠페인 발송 모두 같은 경로.** 두 경우 다 발송 큐를 지나 `dispatchOne`으로
  수렴하므로 스로틀이 자동 적용됩니다. 테스트 발송(파이프라인 우회)만 예외 — 의도된
  동작입니다("내게 먼저 보내기"가 캠페인 뒤에 줄 서면 안 되므로).
- **실측이 설계의 일부.** 단위 테스트 25건(디스패치 18 + 워크스페이스 7)에 더해, 실제
  기동 상태에서 속도를 쟀습니다(5절). 3-3의 NPE처럼 mock 계층에서는 절대 안 보이는
  결함이 실측 단계에서 나왔습니다.

## 5. 확인 방법 (실측 기록 포함)

2026-07-20 로컬 실측(단일 워커, concurrency 8, MailHog):

| 시나리오 | 결과 |
|----------|------|
| rate=5/s, 애드혹 40건 | **7.7초** 드레인 (실효 5.2 msg/s — 첫 1초 버스트 포함) |
| rate=3/s, 60건 | **19.8초** (실효 3.0 msg/s), 실패 0 |
| 위 60건 드레인 중 무제한 테넌트(Acme) 10건 | **0.3초** 완료 (base는 그 시점 9/60) |
| rate 해제 후 5건 | 2.2초 — 무제한 경로 정상 |

재현 절차:

```bash
# 1) 관리 페이지(/settings, ADMIN)에서 "발송 속도 제한"을 5로 저장
#    (또는 PUT /api/workspace 로 sendRatePerSec: 5)

# 2) 수신자 40명짜리 애드혹 캠페인 발송 → 상세 화면 진행률이 초당 ~5씩 증가

# 3) 파킹 큐가 실제로 도는 모습: RabbitMQ UI(localhost:15672)에서
#    mail.send.throttled 의 메시지 수가 출렁이는 것을 관찰

# 4) noisy neighbor: 다른 워크스페이스 계정으로 로그인해 같은 시간대에
#    소규모 캠페인 발송 → 즉시 완료되는지 확인

# 5) 버킷 상태 직접 보기
psql -h localhost -U maildb maildb \
  -c "select * from workspace_send_buckets;"
```

## 연관 문서
- [02-campaign-queue-rabbitmq.md](02-campaign-queue-rabbitmq.md) — 발송 큐·팬아웃 토폴로지 (파킹 큐가 얹힌 기반)
- [03-dispatch-suppression.md](03-dispatch-suppression.md) — dispatchOne의 나머지 경로 (claim·억제·발송)
- [10-multitenancy.md](10-multitenancy.md) — 워크스페이스 격리 (이 문서는 그 "운영 격리" 축)
- [../ROADMAP-scale.md](../ROADMAP-scale.md) — Tier 2 ④ 발송 속도 제한 항목
