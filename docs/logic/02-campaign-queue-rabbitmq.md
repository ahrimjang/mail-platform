# 02. 캠페인 생성과 발송 큐 — DB 저장 → RabbitMQ 발행 → 리스너 소비

## 1. 개요 (무엇을, 왜)

이 플랫폼의 정체성이 담긴 흐름입니다: **API는 일을 "접수"만 하고 즉시 응답하고, 실제 발송은 별도 프로세스(worker)가 큐를 소비하며 비동기로 처리합니다.**

수신자가 10만 명인 캠페인을 만들 때, API가 10만 통을 직접 보내면 HTTP 요청이 몇 분씩 걸리고 타임아웃이 납니다. 대신:

1. `POST /api/campaigns` → 캠페인 1건 + 수신자 수만큼의 `MailMessage` 행(모두 `PENDING`)을 **DB에 저장**하고,
2. 메시지 행마다 **RabbitMQ에 "발송 잡"을 1건씩 발행**한 뒤 바로 응답합니다.
3. mail-worker의 `@RabbitListener`가 큐에서 잡을 꺼내 한 건씩 발송합니다(발송 자체는 [03 문서](03-dispatch-suppression.md) 참고).

핵심 아이디어: **큐에는 메시지 id만 흘려보냅니다.** 제목/본문/수신자 같은 실제 데이터는 DB가 진실의 원천(source of truth)이고, RabbitMQ는 "이 id를 처리하라"는 신호만 나릅니다.

## 2. 흐름

```
 브라우저          mail-api (CampaignService)              Postgres             RabbitMQ              mail-worker
    │ POST /api/campaigns    │                              │                      │                      │
    ├───────────────────────>│ ① campaign 저장 (QUEUED) ───>│                      │                      │
    │                        │ ② 수신자별 MailMessage 저장 ─>│ (전부 PENDING)       │                      │
    │                        │ ③ 행마다 enqueue(id) ───────────────────────────────>│ mail.exchange        │
    │  201 { campaignId }    │                              │                      │   │routing:mail.send │
    │<───────────────────────┤ (여기서 끝. 발송은 안 함)     │                      │   ▼                  │
    │                        │                              │                mail.send.queue             │
    │                        │                              │                      │  ④ SendJob{id} ─────>│ MailSendListener
    │                        │                              │<──────────────────────────────────────────── │ dispatchOne(id)
    │ GET /api/campaigns/{id}│                              │  (id로 행 조회→발송→상태 갱신)                │
    ├───────────────────────>│ ⑤ 카운트 집계로 진행률 응답   │                      │                      │
    │<───────────────────────┤                              │              (실패 3회 → mail.send.dlq)     │
```

위 그림은 **즉시 발송** 기준입니다. 요청에 미래의 `scheduledAt`이 있으면 ③(큐 발행)만 보류되고, worker의 스케줄러가 시각 도래 시 대신 발행합니다 — [6장 예약 발송](#6-예약-발송--scheduledat-보류와-원자적-릴리스) 참고.

## 3. 단계별 실제 코드

### 3-1. 접수 — CampaignService.create()

요청 DTO에는 발신자 정보와 예약 시각이 추가되어 있습니다.

`mail-common/src/main/java/io/github/ahrimjang/mail/common/CreateCampaignRequest.java`
```java
public record CreateCampaignRequest(
        String subject,
        String body,
        List<String> recipients,
        Long templateId,
        Long listId,
        String senderName,
        String senderEmail,
        Instant scheduledAt
) {
}
```

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignService.java`
```java
        // A future scheduledAt defers the queue release; null or past sends now.
        Instant now = Instant.now();
        boolean deferred = request.scheduledAt() != null && request.scheduledAt().isAfter(now);

        Campaign campaign = Campaign.draft(subject, body);
        campaign.setStatus(CampaignStatus.QUEUED);
        campaign.setSenderName(blankToNull(request.senderName()));
        campaign.setSenderEmail(blankToNull(request.senderEmail()));
        campaign.setScheduledAt(request.scheduledAt());
        // Immediate campaigns are released right here; scheduled ones keep
        // enqueuedAt null so the worker's scheduler claims them when due.
        campaign.setEnqueuedAt(deferred ? null : now);
        Campaign saved = campaigns.save(campaign);

        List<MailMessage> queued;
        if (request.listId() != null) {
            List<Contact> members = contacts.findByListId(request.listId());
            if (members.isEmpty()) {
                throw new IllegalArgumentException("list has no members: " + request.listId());
            }
            queued = members.stream()
                    .map(c -> MailMessage.queued(saved.getId(), c.getEmail(), c.getId()))
                    .toList();
        } else {
            if (request.recipients() == null || request.recipients().isEmpty()) {
                throw new IllegalArgumentException("recipients must not be empty");
            }
            queued = request.recipients().stream()
                    .map(recipient -> MailMessage.queued(saved.getId(), recipient))
                    .toList();
        }
        List<MailMessage> savedMessages = messages.saveAll(queued);
        if (!deferred) {
            savedMessages.forEach(m -> mailQueue.enqueue(m.getId()));
        }

        return toView(saved);
```

읽는 순서 그대로가 흐름입니다: 캠페인 저장 → 수신자 목록(직접 입력 or 연락처 리스트)을 `MailMessage` 행으로 **팬아웃(fan-out)** → 전부 저장 → **저장된 각 행의 id를 큐에 발행** → 즉시 반환. 발송 코드는 이 파일에 한 줄도 없습니다.

수신자는 두 갈래로 옵니다: `listId`가 있으면 M4 연락처 리스트의 멤버를, 없으면 요청에 직접 담긴 `recipients` 배열을 씁니다. (제목/본문도 마찬가지로 `templateId`가 있으면 템플릿에서 가져옵니다 — 같은 파일의 `create()` 앞부분 참고.)

`senderName`/`senderEmail`은 선택적 From 오버라이드로 캠페인에 저장돼 발송 시점에 `MailSender`로 전달됩니다(비면 SMTP 기본값). 그리고 **`scheduledAt`이 미래이면 마지막 `enqueue` 단계만 보류됩니다** — 캠페인과 PENDING 행은 똑같이 저장하되 `enqueuedAt`을 `null`로 남기고 RabbitMQ에는 아무것도 발행하지 않습니다. 보류된 캠페인을 시각 도래 시 큐로 풀어주는 흐름은 [6장](#6-예약-발송--scheduledat-보류와-원자적-릴리스)에서 다룹니다.

### 3-2. 포트 — 큐에는 id만 지나간다

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/MailQueue.java`
```java
/**
 * Outbound port for handing a queued message id to the send queue.
 *
 * <p>The message body/state lives in the store; only the id travels through
 * the queue. A broker adapter (RabbitMQ) implements this in the infra module.
 */
public interface MailQueue {

    /** Enqueue one send job for the given message id. */
    void enqueue(Long messageId);
}
```

큐로 흘러가는 페이로드도 극단적으로 단순합니다.

`mail-common/src/main/java/io/github/ahrimjang/mail/common/SendJob.java`
```java
/**
 * one send job = one queued message id
 */
public record SendJob(Long messageId) {
}
```

### 3-3. 어댑터 — RabbitMQ에 발행

`infra/src/main/java/io/github/ahrimjang/mail/infra/messaging/RabbitMailQueue.java`
```java
@Component
public class RabbitMailQueue implements MailQueue {

    private final RabbitTemplate rabbitTemplate;

    public RabbitMailQueue(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void enqueue(Long messageId) {
        rabbitTemplate.convertAndSend(RabbitMailConfig.EXCHANGE, RabbitMailConfig.ROUTING_KEY, new SendJob(messageId));
    }
}
```

`convertAndSend`가 `SendJob`을 JSON으로 직렬화해(아래 `Jackson2JsonMessageConverter` 덕분) `mail.exchange`에 `mail.send` 라우팅 키로 발행합니다.

### 3-4. 토폴로지 — 익스체인지/큐/DLQ 선언

`infra/src/main/java/io/github/ahrimjang/mail/infra/messaging/RabbitMailConfig.java`
```java
    public static final String EXCHANGE = "mail.exchange";
    public static final String QUEUE = "mail.send.queue";
    public static final String ROUTING_KEY = "mail.send";
    public static final String DLX = "mail.dlx";
    public static final String DLQ = "mail.send.dlq";
    public static final String DLQ_ROUTING = "mail.send.dlq";

    @Bean
    public DirectExchange mailExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue mailQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING)
                .build();
    }

    @Bean
    public Binding mailBinding() {
        return BindingBuilder.bind(mailQueue()).to(mailExchange()).with(ROUTING_KEY);
    }
```

RabbitMQ 용어를 풀면: **익스체인지**는 우체국(메시지를 받아 어디로 보낼지 결정), **큐**는 사서함(소비자가 꺼낼 때까지 대기), **바인딩**은 "라우팅 키가 `mail.send`인 메시지는 `mail.send.queue`로" 라는 배달 규칙입니다. `durable(true)`라서 브로커가 재시작해도 큐/메시지가 남습니다.

`x-dead-letter-*` 인자가 중요합니다: 소비자가 메시지를 **거부(reject)** 하면 버려지는 대신 `mail.dlx` → `mail.send.dlq`로 이동합니다(사후 분석/재처리용 "불량품 상자"). DLX/DLQ 빈 선언은 같은 파일 아래쪽에 있습니다.

### 3-5. 소비 — mail-worker의 리스너

`mail-worker/src/main/java/io/github/ahrimjang/mail/worker/MailSendListener.java`
```java
@Component
public class MailSendListener {

    private final MailDispatchService dispatch;

    public MailSendListener(MailDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @RabbitListener(queues = RabbitMailConfig.QUEUE)
    public void onSendJob(SendJob job) {
        dispatch.dispatchOne(job.messageId());
    }
}
```

`@RabbitListener`가 붙은 메서드는 Spring이 알아서 큐를 구독시켜 줍니다. 잡이 도착할 때마다 `dispatchOne(id)`이 호출되고, 메서드가 정상 리턴하면 ack(처리 완료), 예외가 나면 아래 재시도 정책을 탑니다.

### 3-6. 소비자 정책 — worker 설정

`mail-worker/src/main/resources/application.yml`
```yaml
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        prefetch: 10
        default-requeue-rejected: false
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 2000
          multiplier: 2.0
```

(접속 정보는 `${ENV_VAR:기본값}` 플레이스홀더입니다 — 로컬 개발은 기본값으로 compose 인프라에 붙고, 프로덕션은 환경변수를 주입합니다. 전체 목록은 `.env.example`.)

- `prefetch: 10` — 워커가 한 번에 미리 받아두는 미확인(unacked) 메시지 수. 처리 속도 조절 밸브입니다.
- `retry: max-attempts: 3, initial-interval: 2000, multiplier: 2.0` — 리스너가 예외를 던지면 2초 → 4초 간격으로 총 3회까지 재시도.
- `default-requeue-rejected: false` — 3회 모두 실패하면 큐에 되돌리지(무한루프) 않고 **거부** → DLQ 인자 덕분에 `mail.send.dlq`로 이동.

### 3-7. 진행 조회 부가 API — 집계 발송 로그와 드릴다운

기본 폴링(`GET /api/campaigns/{id}`, 카운트 집계)에 더해, 캠페인 상세 화면용 조회 API가 두 개 있습니다.

`mail-api/src/main/java/io/github/ahrimjang/mail/api/CampaignController.java`
```java
    /** Per-recipient drill-down: the campaign's most recently updated deliveries, newest first. */
    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable Long id,
                                      @RequestParam(defaultValue = "50") int limit) {
        return campaigns.recentMessages(id, limit);
    }

    /** Aggregated send log: time-bucketed counts per outcome — stays short for huge campaigns. */
    @GetMapping("/{id}/log")
    public List<SendLogEntry> log(@PathVariable Long id,
                                  @RequestParam(defaultValue = "10") int bucketSeconds,
                                  @RequestParam(defaultValue = "50") int limit) {
        return campaigns.sendLog(id, bucketSeconds, limit);
    }
```

- **`/messages`** — 수신자별 드릴다운: 최근 상태가 바뀐 메시지부터 `MessageView(id, recipient, status, errorMessage, updatedAt)`를 최신순으로 돌려줍니다(limit 최대 200).
- **`/log`** — 집계 발송 로그: 개별 행이 아니라 **고정 시간 버킷 × 상태별 건수**("N건 SENT", "M건 BOUNCED — 대표 에러")로 묶어서 돌려줍니다. 10만 통짜리 캠페인이어도 응답 크기가 유계(bounded)입니다.

버킷 집계는 애플리케이션이 아니라 **DB에서** 합니다 — 네이티브 `GROUP BY` 쿼리 하나로 끝냅니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/MailMessageJpaRepository.java`
```java
    @Query(value = """
            select floor(extract(epoch from m.updated_at) / :bucketSeconds) as bucket,
                   m.status as status,
                   count(*) as cnt,
                   min(m.error_message) as sample_error
            from mail_messages m
            where m.campaign_id = :campaignId
            group by bucket, m.status
            order by bucket desc, m.status
            limit :limit
            """, nativeQuery = true)
    java.util.List<Object[]> aggregateLogByCampaign(@Param("campaignId") Long campaignId,
                                                    @Param("bucketSeconds") int bucketSeconds,
                                                    @Param("limit") int limit);
```

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignService.java`
```java
    public List<SendLogEntry> sendLog(Long campaignId, int bucketSeconds, int limit) {
        campaigns.findById(campaignId)
                .orElseThrow(() -> new NoSuchElementException("campaign not found: " + campaignId));
        int bucket = Math.max(1, Math.min(bucketSeconds, 3600));
        int capped = Math.max(1, Math.min(limit, 200));
        return messages.aggregateLogByCampaign(campaignId, bucket, capped).stream()
                .map(b -> new SendLogEntry(b.bucketStart(), b.status(), b.count(), b.sampleError()))
                .toList();
    }
```

`bucketSeconds`(1~3600초)와 `limit`(최대 200)을 서비스에서 강제로 클램프하므로, 클라이언트가 어떤 값을 보내도 쿼리가 폭주하지 않습니다.

## 4. 설계 포인트 (왜 이렇게)

- **id만 큐에 태우는 이유**: 본문을 통째로 큐에 실으면 DB와 큐 두 곳에 진실이 생겨 어긋날 수 있습니다. id만 나르면 소비 시점에 DB에서 최신 상태를 읽으므로 항상 일관됩니다. 메시지도 가볍습니다.
- **at-least-once와 멱등성**: RabbitMQ는 "최소 1회 전달"을 보장합니다 — 즉 같은 잡이 **두 번** 올 수 있습니다. 그래서 소비 측 `dispatchOne`이 "이미 PENDING이 아니면 스킵"하는 멱등 설계입니다(03 문서 참고). 큐를 믿는 게 아니라 DB 상태를 믿습니다.
- **DLQ**: 계속 실패하는 잡(포이즌 메시지)이 큐를 막고 무한 재시도되는 것을 막는 표준 패턴. 운영자는 DLQ만 모니터링하면 됩니다.
- **포트 덕분에 브로커 교체 가능**: `CampaignService`는 `MailQueue` 인터페이스만 봅니다. SQS/Kafka로 바꾸려면 `infra`에 어댑터 하나만 새로 쓰면 됩니다.
- **발송 로그는 DB에서 집계**: `/log`가 메시지 행을 전부 끌어와 애플리케이션에서 묶는 대신 네이티브 `GROUP BY`로 버킷 집계를 DB에 맡깁니다. 캠페인이 아무리 커도 네트워크로 오가는 건 최대 200행입니다.
- **남은 한계**: DB 저장과 큐 발행이 한 트랜잭션이 아니므로, 저장 직후 프로세스가 죽으면 "행은 있는데 잡이 없는" 메시지가 남을 수 있습니다(outbox 패턴이 프로덕션 해법).

## 5. 확인 방법

RabbitMQ + MailHog + mail-api + mail-worker를 띄운 상태에서:

```bash
# RabbitMQ (관리 UI 포함) 실행
docker run -d --rm --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management

# 로그인 토큰 확보 (01 문서 참고) 후 캠페인 생성 → 즉시 201 응답
curl -s -X POST http://localhost:8080/api/campaigns \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"subject":"hello","body":"<p>hi <a href=\"http://example.com\">link</a></p>","recipients":["a@test.com","b@test.com","bad-address"]}'

# 진행 상황 폴링 — pending이 줄고 sent/bounced가 늘어난다
curl -s http://localhost:8080/api/campaigns/1 -H "Authorization: Bearer $TOKEN"

# 집계 발송 로그 (10초 버킷 × 상태별 건수) / 수신자별 드릴다운
curl -s "http://localhost:8080/api/campaigns/1/log?bucketSeconds=10&limit=50" -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/campaigns/1/messages?limit=50" -H "Authorization: Bearer $TOKEN"
```

눈으로 보는 곳들:

- **RabbitMQ 관리 UI** `http://localhost:15672` (guest/guest) → Queues 탭에서 `mail.send.queue`에 메시지가 쌓였다 빠지는 것, `mail.send.dlq`에 실패분이 남는 것을 볼 수 있습니다.
- **MailHog** `http://localhost:8025` → 실제 도착한 메일 확인.
- **재미있는 실험**: mail-worker를 끈 채 캠페인을 만들면 잡이 큐에 쌓여만 있고(`pending` 그대로), worker를 켜는 순간 소비가 시작됩니다 — "API와 발송이 분리되어 있다"를 몸으로 확인하는 방법입니다.

## 6. 예약 발송 — scheduledAt 보류와 원자적 릴리스

### 6-1. 개요 (무엇을, 왜)

"내일 아침 9시에 보내줘"를 지원합니다. 핵심 아이디어는 **새 파이프라인을 만들지 않는 것**: 예약 캠페인도 생성 시점에 캠페인 + PENDING 메시지 행을 전부 저장하고, 오직 **RabbitMQ 발행 한 단계만 보류**합니다. 시각이 되면 worker의 스케줄러가 보류된 발행을 대신 수행하고, 그 이후는 즉시 발송과 완전히 동일한 흐름(3-5의 리스너 → dispatchOne)을 탑니다.

"보류됨/릴리스됨"의 판별자는 `campaigns.enqueued_at` 컬럼 하나입니다:

- 즉시 발송: 생성 시 `enqueuedAt = now` 스탬프 + 바로 발행.
- 예약 발송: `enqueuedAt = NULL`로 저장, 발행 없음. 스케줄러가 시각 도래 시 **`enqueued_at IS NULL` 조건부 UPDATE**로 스탬프를 찍는 데 성공한(claim을 이긴) 워커만 발행합니다 — 워커가 여러 대여도 정확히 1회.

### 6-2. 흐름

```
 브라우저        mail-api (CampaignService)          Postgres                mail-worker (10초 주기)
    │ POST /api/campaigns        │                      │                           │
    │  { scheduledAt: 미래 }     │                      │                           │
    ├───────────────────────────>│ campaign 저장 ───────>│ enqueued_at = NULL        │
    │                            │ MailMessage 저장 ────>│ (전부 PENDING)            │
    │  201 (RabbitMQ 발행 없음!) │                      │                           │
    │<───────────────────────────┤                      │                           │
    │                                                   │      ScheduledCampaignReleaser (@Scheduled)
    │                                                   │<── ① findDueForEnqueue(now) ──┤ 시각 도래 + 미릴리스 조회
    │                                                   │<── ② claimForEnqueue(id) ─────┤ 조건부 UPDATE (1행 or 0행)
    │                                                   │<── ③ PENDING id 목록 조회 ────┤ claim을 이긴 워커만
    │                                                   │    ④ id마다 enqueue ──> RabbitMQ ──> MailSendListener
    │                                                   │       (여기부터는 즉시 발송과 동일)
```

### 6-3. 단계별 실제 코드

**접수 측**은 3-1에서 이미 봤습니다 — `deferred`면 `enqueuedAt`을 `null`로 두고 마지막 `enqueue` 루프를 건너뜁니다.

**스키마**: 발신자/예약 컬럼은 Flyway V2 마이그레이션으로 추가됐습니다.

`infra/src/main/resources/db/migration/V2__campaign_sender_and_schedule.sql`
```sql
ALTER TABLE campaigns ADD COLUMN sender_name  varchar(255);
ALTER TABLE campaigns ADD COLUMN sender_email varchar(255);
ALTER TABLE campaigns ADD COLUMN scheduled_at timestamptz(6);
ALTER TABLE campaigns ADD COLUMN enqueued_at  timestamptz(6);

UPDATE campaigns SET enqueued_at = created_at WHERE enqueued_at IS NULL;

-- The scheduler polls for due, unreleased campaigns.
CREATE INDEX idx_campaigns_due ON campaigns (scheduled_at) WHERE enqueued_at IS NULL;
```

기존 캠페인은 전부 생성 시 발행된 것이므로 `created_at`을 `enqueued_at`으로 백필합니다 — 안 하면 스케줄러가 옛 캠페인을 "아직 안 풀린 것"으로 오인합니다. 마지막 줄은 **부분 인덱스**(partial index): `enqueued_at IS NULL`인 행(= 아직 안 풀린 예약분)만 인덱싱하므로 10초마다 도는 폴링 쿼리가 사실상 공짜입니다.

**포트**: 도메인은 여기서도 인터페이스만 봅니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/CampaignRepository.java`
```java
    /**
     * Scheduled campaigns whose send time has arrived but whose messages have
     * not been released to the queue yet ({@code enqueuedAt} is null and
     * {@code scheduledAt <= now}).
     */
    List<Campaign> findDueForEnqueue(Instant now);

    /**
     * Atomically claims a due campaign for release by stamping {@code enqueuedAt}
     * (single conditional update on {@code enqueuedAt IS NULL} — the database
     * serializes concurrent schedulers so only one wins).
     *
     * @return true if this call won the claim; false means another scheduler
     *         already released it — the caller must skip, not error.
     */
    boolean claimForEnqueue(Long id, Instant now);
```

**어댑터 — 원자적 claim**: 03 문서의 `claimPending`과 같은 패턴입니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/CampaignJpaRepository.java`
```java
    /** Scheduled campaigns that are due but not yet released to the queue. */
    @Query("select c from CampaignEntity c where c.enqueuedAt is null and c.scheduledAt <= :now")
    List<CampaignEntity> findDueForEnqueue(@Param("now") Instant now);

    /**
     * Single conditional UPDATE claiming a due campaign for release — same
     * pattern as {@link MailMessageJpaRepository#claimPending}: the database
     * serializes concurrent schedulers, so exactly one caller updates the row
     * and gets to publish the send jobs.
     */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.enqueuedAt = :now where c.id = :id and c.enqueuedAt is null")
    int claimForEnqueue(@Param("id") Long id, @Param("now") Instant now);
```

**유스케이스 — 릴리스 로직** (mail-core):

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignScheduleService.java`
```java
    public int releaseDue() {
        Instant now = Instant.now();
        List<Campaign> due = campaigns.findDueForEnqueue(now);
        int released = 0;
        for (Campaign campaign : due) {
            if (!campaigns.claimForEnqueue(campaign.getId(), now)) {
                continue; // another scheduler won the race
            }
            List<Long> pendingIds = messages.findPendingIdsByCampaign(campaign.getId());
            pendingIds.forEach(mailQueue::enqueue);
            released++;
            log.info("released scheduled campaign {} ({} messages) scheduledAt={}",
                    campaign.getId(), pendingIds.size(), campaign.getScheduledAt());
        }
        return released;
    }
```

조회는 느슨하게(여러 워커가 같은 캠페인을 볼 수 있음), 결정은 엄격하게(claim에 이긴 하나만 발행). `findPendingIdsByCampaign`은 id만 뽑아옵니다 — 발행에 필요한 건 id뿐이니까요(3-2의 "큐에는 id만" 원칙 그대로).

**트리거 — worker의 @Scheduled** (주기만 소유하고 로직은 core에 위임):

`mail-worker/src/main/java/io/github/ahrimjang/mail/worker/ScheduledCampaignReleaser.java`
```java
@Component
public class ScheduledCampaignReleaser {

    private final CampaignScheduleService scheduleService;

    public ScheduledCampaignReleaser(CampaignScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 10s cadence: fine-grained enough for a send scheduler, cheap on the DB (indexed partial scan). */
    @Scheduled(fixedDelay = 10_000)
    public void releaseDueCampaigns() {
        scheduleService.releaseDue();
    }
}
```

### 6-4. 설계 포인트 (왜 이렇게)

- **파이프라인 재사용**: 예약은 "발행 시점만 미룬 것"입니다. 릴리스 이후의 소비/멱등/재시도/DLQ는 즉시 발송과 한 글자도 다르지 않고, `dispatchOne`의 claim 방어도 그대로 유효합니다.
- **정확히 1회 릴리스**: 락 서버나 리더 선출 없이, `enqueued_at IS NULL` 조건부 UPDATE 하나로 다중 워커 경쟁을 DB가 직렬화합니다. 진 쪽은 0행 갱신 → 조용히 스킵.
- **crash 내성**: claim과 발행 사이에서 워커가 죽으면 그 캠페인의 메시지는 PENDING인 채 남습니다(잡만 없음). `enqueued_at`이 이미 찍혀 자동 재시도는 안 되지만, 상태가 DB에 그대로 있으므로 수동 복구가 가능합니다 — POC에서 감수한 트레이드오프입니다(outbox 패턴이 프로덕션 해법인 것과 같은 맥락).
- **10초 폴링**: 메일 예약에 초단위 정밀도는 과합니다. 부분 인덱스 덕에 폴링 비용은 무시할 수준이고, 브로커의 delayed-message 플러그인 같은 인프라 의존을 하나 줄입니다.

### 6-5. 확인 방법

```bash
# 2분 뒤로 예약한 캠페인 생성 (scheduledAt은 ISO-8601 UTC)
curl -s -X POST http://localhost:8080/api/campaigns \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"subject\":\"scheduled hello\",\"body\":\"<p>later</p>\",\"recipients\":[\"a@test.com\"],\"scheduledAt\":\"$(date -u -d '+2 minutes' +%Y-%m-%dT%H:%M:%SZ)\"}"

# 즉시 확인 — pending은 쌓여 있는데 RabbitMQ 큐는 비어 있다 (발행이 보류된 상태)
curl -s http://localhost:8080/api/campaigns/2 -H "Authorization: Bearer $TOKEN"
```

- **RabbitMQ 관리 UI** (`http://localhost:15672`) → 예약 시각 전에는 `mail.send.queue`가 조용하다가, 시각이 지나고 10초 이내에 메시지가 확 들어왔다 빠집니다.
- **DB로 직접**: `psql -h localhost -U maildb maildb` → `select id, scheduled_at, enqueued_at from campaigns;` — 릴리스 전에는 `enqueued_at`이 NULL, 스케줄러가 풀어주는 순간 스탬프가 찍힙니다.
- **worker 로그**: `released scheduled campaign 2 (1 messages) scheduledAt=...` 한 줄이 릴리스의 증거입니다.
