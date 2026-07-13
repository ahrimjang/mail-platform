# 02. 캠페인 생성과 발송 큐 — DB 저장 → RabbitMQ 발행 → 리스너 소비

## 1. 개요 (무엇을, 왜)

이 플랫폼의 정체성이 담긴 흐름입니다: **API는 일을 "접수"만 하고 즉시 응답하고, 실제 발송은 별도 프로세스(worker)가 큐를 소비하며 비동기로 처리합니다.**

수신자가 10만 명인 캠페인을 만들 때, API가 10만 통을 직접 보내면 HTTP 요청이 몇 분씩 걸리고 타임아웃이 납니다. 심지어 10만 개의 `MailMessage` 행을 만드는 것조차 요청 스레드에서 하면 무겁습니다. 대신:

1. `POST /api/campaigns` → 캠페인 1건(`QUEUED`)을 **DB에 저장**하고,
2. **리스트 캠페인**(`listId`)이면 수신자를 펼치지 않고 **RabbitMQ에 "팬아웃(fan-out) 잡" 1건만 발행**한 뒤 바로 응답합니다 — 응답 시간이 리스트 크기와 무관한 O(1). 워커가 이 잡을 받아 수신자를 `MailMessage` 행으로 비동기 확장합니다(아래 3-6). **애드혹 캠페인**(요청 본문의 `recipients[]`)이면 개수가 요청 크기로 유계이므로 그 자리에서 행을 만들고 행마다 발송 잡을 발행합니다.
3. mail-worker의 `@RabbitListener`가 큐에서 발송 잡을 꺼내 한 건씩 발송합니다(발송 자체는 [03 문서](03-dispatch-suppression.md) 참고).

핵심 아이디어: **큐에는 메시지 id만 흘려보냅니다.** 제목/본문/수신자 같은 실제 데이터는 DB가 진실의 원천(source of truth)이고, RabbitMQ는 "이 id를 처리하라"는 신호만 나릅니다.

## 2. 흐름

```
 브라우저          mail-api (CampaignService)              Postgres             RabbitMQ              mail-worker
    │ POST /api/campaigns    │                              │                      │                      │
    │  { listId }            │ ① campaign 저장 (QUEUED) ───>│                      │                      │
    ├───────────────────────>│ ② enqueueFanout(campaignId) ───────────────────────>│ mail.exchange        │
    │  201 { campaignId }    │   (수신자 확장 없음 — O(1))   │                      │  routing:mail.fanout │
    │<───────────────────────┤ (여기서 끝. 발송도 확장도 안 함)                     │   ▼                  │
    │                        │                              │              mail.fanout.queue              │
    │                        │                              │                      │ ③ FanoutJob{cid} ───>│ CampaignFanoutListener
    │                        │                              │<── ④ QUEUED→EXPANDING claim ─────────────────┤ expand(cid)
    │                        │                              │<── ⑤ 수신자를 1000행씩 스트리밍:              │
    │                        │                              │      MailMessage 저장 + 행마다 enqueue(id) ─>│ mail.exchange
    │                        │                              │<── ⑥ EXPANDING→SENDING (+드레인됐으면 완료)   │  routing:mail.send
    │                        │                              │                      │   ▼                  │
    │                        │                              │                mail.send.queue              │
    │                        │                              │                      │ ⑦ SendJob{id} ──────>│ MailSendListener
    │                        │                              │<──────────────────────────────────────────── │ dispatchOne(id)
    │ GET /api/campaigns/{id}│                              │  (id로 행 조회→발송→상태 갱신)                │
    ├───────────────────────>│ ⑧ 카운트 집계로 진행률 응답   │                      │                      │
    │<───────────────────────┤                              │              (실패 3회 → mail.send.dlq)     │
```

**애드혹 캠페인**(`recipients[]`)은 팬아웃을 건너뜁니다 — `create()`가 그 자리에서 `MailMessage` 행을 만들어 곧장 ⑦의 `mail.send.queue`로 발행하므로 ③~⑥ 단계가 없습니다(개수가 요청 크기로 유계라 요청 스레드에서 펼쳐도 안전).

위 그림은 **즉시 발송** 기준입니다. 요청에 미래의 `scheduledAt`이 있으면 ②(팬아웃/발송 잡 발행)만 보류되고, worker의 스케줄러가 시각 도래 시 대신 발행합니다 — [6장 예약 발송](#6-예약-발송--scheduledat-보류와-원자적-릴리스) 참고.

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
        campaign.setTemplateId(request.templateId());
        campaign.setListId(request.listId());
        Campaign saved = campaigns.save(campaign);

        if (request.listId() != null) {
            // Large list campaigns fan out asynchronously: persist only the campaign
            // and hand a single fan-out job to the worker, so create() is O(1) in the
            // recipient count. Scheduled campaigns publish the fan-out job at release
            // time (see CampaignScheduleService); immediate ones publish it now.
            if (contacts.countByListId(request.listId()) == 0) {
                throw new IllegalArgumentException("list has no members: " + request.listId());
            }
            if (!deferred) {
                mailQueue.enqueueFanout(saved.getId());
            }
        } else {
            if (request.recipients() == null || request.recipients().isEmpty()) {
                throw new IllegalArgumentException("recipients must not be empty");
            }
            // Ad-hoc recipient lists are bounded by the request body — expand inline.
            List<MailMessage> queued = request.recipients().stream()
                    .map(recipient -> MailMessage.queued(saved.getId(), recipient))
                    .toList();
            List<MailMessage> savedMessages = messages.saveAll(queued);
            if (!deferred) {
                savedMessages.forEach(m -> mailQueue.enqueue(m.getId()));
            }
        }

        return toView(saved);
```

읽는 순서 그대로가 흐름입니다: 캠페인 저장 → **리스트냐 애드혹이냐로 갈라집니다.** `listId`가 있으면 수신자를 여기서 펼치지 않고 멤버가 하나라도 있는지만 확인한 뒤(`countByListId`) 워커에 **팬아웃 잡 1건**을 넘깁니다 — 100만 행짜리 리스트여도 `create()`는 리스트 크기와 무관한 O(1)입니다. 실제 `MailMessage` 팬아웃과 발송 잡 발행은 워커가 비동기로 합니다(아래 3-6). `recipients[]`로 직접 준 애드혹 캠페인은 개수가 요청 크기로 유계이므로 그 자리에서 행을 만들어 저장하고 각 행 id를 큐에 발행합니다. 어느 쪽이든 발송 코드는 이 파일에 한 줄도 없습니다.

(제목/본문도 마찬가지로 `templateId`가 있으면 템플릿에서 가져옵니다 — 같은 파일의 `create()` 앞부분 참고.)

`senderName`/`senderEmail`은 선택적 From 오버라이드로 캠페인에 저장돼 발송 시점에 `MailSender`로 전달됩니다(비면 SMTP 기본값). 그리고 **`scheduledAt`이 미래이면 마지막 발행 단계만 보류됩니다** — 캠페인은 똑같이 저장하되(애드혹이면 PENDING 행도 함께 저장) `enqueuedAt`을 `null`로 남기고 RabbitMQ에는 아무것도 발행하지 않습니다(팬아웃 잡도, 발송 잡도). 보류된 캠페인을 시각 도래 시 큐로 풀어주는 흐름은 [6장](#6-예약-발송--scheduledat-보류와-원자적-릴리스)에서 다룹니다.

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

    /** Enqueue a fan-out job so the worker expands a list campaign's recipients. */
    void enqueueFanout(Long campaignId);
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
    public static final String FANOUT_QUEUE = "mail.fanout.queue";
    public static final String FANOUT_ROUTING_KEY = "mail.fanout";

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

    @Bean
    public Queue fanoutQueue() {
        return QueueBuilder.durable(FANOUT_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING)
                .build();
    }

    @Bean
    public Binding fanoutBinding() {
        return BindingBuilder.bind(fanoutQueue()).to(mailExchange()).with(FANOUT_ROUTING_KEY);
    }
```

RabbitMQ 용어를 풀면: **익스체인지**는 우체국(메시지를 받아 어디로 보낼지 결정), **큐**는 사서함(소비자가 꺼낼 때까지 대기), **바인딩**은 "라우팅 키가 `mail.send`인 메시지는 `mail.send.queue`로" 라는 배달 규칙입니다. `durable(true)`라서 브로커가 재시작해도 큐/메시지가 남습니다.

**같은 `mail.exchange`에 큐가 하나 더 매달려 있습니다**: `mail.fanout.queue`는 라우팅 키 `mail.fanout`으로 바인딩돼 리스트 캠페인의 **팬아웃 잡**(`FanoutJob{campaignId}`)을 받습니다 — 발송 잡(`mail.send`)과 팬아웃 잡(`mail.fanout`)이 하나의 익스체인지에서 라우팅 키로 갈라져 각자의 큐로 갑니다. 팬아웃 큐도 발송 큐와 **동일한 DLX/DLQ로 데드레터링**하므로, 계속 실패하는 팬아웃 잡도 같은 `mail.send.dlq`에 격리됩니다. 팬아웃 잡을 소비해 수신자를 펼치는 리스너는 [3-6](#3-6-팬아웃-확장--리스트-캠페인을-워커가-비동기로-펼친다)에서 다룹니다.

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

### 3-6. 팬아웃 확장 — 리스트 캠페인을 워커가 비동기로 펼친다

리스트 캠페인의 `create()`는 수신자를 펼치지 않고 팬아웃 잡 1건만 남겼습니다(3-1). 그 잡을 받아 실제 `MailMessage` 행을 만드는 곳이 여기입니다 — 무거운 N행 확장을 요청 경로 밖으로 밀어낸 "지연된 나머지 절반"입니다.

`mail-worker/src/main/java/io/github/ahrimjang/mail/worker/CampaignFanoutListener.java`
```java
    @RabbitListener(queues = RabbitMailConfig.FANOUT_QUEUE)
    public void onFanoutJob(FanoutJob job) {
        fanout.expand(job.campaignId());
    }
```

`mail.send.queue`를 구독하는 `MailSendListener`(3-5)와 완전히 대칭입니다 — 이쪽은 `mail.fanout.queue`를 구독하고, 잡 1건마다 `expand(campaignId)`를 호출합니다. 로직은 core의 `CampaignFanoutService`에 있습니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignFanoutService.java`
```java
    public void expand(Long campaignId) {
        if (!campaigns.claimForFanout(campaignId)) {
            log.debug("skip fan-out: campaign {} already claimed/expanded by another consumer", campaignId);
            return;
        }
        Campaign campaign = campaigns.findById(campaignId).orElse(null);
        if (campaign == null || campaign.getListId() == null) {
            return;
        }
        Long listId = campaign.getListId();

        long afterId = 0L;
        long total = 0;
        while (true) {
            List<Contact> page = contacts.findByListIdAfter(listId, afterId, PAGE);
            if (page.isEmpty()) {
                break;
            }
            List<MailMessage> batch = page.stream()
                    .map(c -> MailMessage.queued(campaignId, c.getEmail(), c.getId()))
                    .toList();
            List<MailMessage> saved = messages.saveAll(batch);
            saved.forEach(m -> mailQueue.enqueue(m.getId()));
            total += saved.size();
            afterId = page.get(page.size() - 1).getId();
            if (page.size() < PAGE) {
                break;
            }
        }

        campaigns.markExpanded(campaignId); // EXPANDING -> SENDING
        // If every message already drained before we flipped to SENDING (fast sends /
        // empty list), finish it here — cheap EXISTS, not a full count.
        if (!messages.hasPendingOrSending(campaignId)) {
            campaigns.completeIfSending(campaignId);
        }
        log.info("fanned out campaign {} into {} messages", campaignId, total);
    }
```

세 가지가 핵심입니다:

- **멱등한 QUEUED→EXPANDING 클레임**: RabbitMQ는 at-least-once라 같은 팬아웃 잡이 두 번 올 수 있습니다. `claimForFanout`이 조건부 UPDATE 한 방으로 캠페인을 `QUEUED`에서 `EXPANDING`으로 넘기고, 진 재배달분은 조용히 스킵합니다 — 03 문서의 메시지 claim과 같은 패턴을 캠페인 상태에 적용한 것입니다. 이게 없으면 재배달이 수신자 행을 두 배로 만들 수 있습니다.
- **키셋 페이지네이션 스트리밍**: 리스트 전체를 메모리에 올리지 않고 `findByListIdAfter(listId, afterId, PAGE=1000)`로 id 오름차순 1000행씩 끊어 읽습니다. 배치마다 `saveAll` + 행 id를 `mail.send.queue`에 발행하므로, 100만 행 리스트여도 워커 메모리는 유계이고 발송은 첫 배치부터 곧바로 시작됩니다.
- **EXPANDING→SENDING 플립과 조기완료 방지**: 새 캠페인 상태 `EXPANDING`이 `QUEUED`와 `SENDING` 사이에 끼어들어, 팬아웃이 도는 동안 캠페인이 조기 완료되는 것을 막습니다(뒤 배치의 메시지는 아직 생성 전이니까). 확장이 끝나면 `markExpanded`로 `SENDING`으로 넘기고, 그 사이 이미 모든 메시지가 드레인됐으면(빠른 발송/빈 리스트) 여기서 완료 처리합니다 — 완료가 `SENDING`에서만 발화하는 이유는 [03 문서 3-5](03-dispatch-suppression.md#3-5-캠페인-완료-판정--completeifdrained) 참고.

상태 머신에 한 칸이 늘었습니다: 리스트 캠페인은 `QUEUED → EXPANDING → SENDING → COMPLETED`, 애드혹 캠페인은 팬아웃이 없으므로 그대로 `QUEUED → SENDING → COMPLETED`.

### 3-7. 소비자 정책 — worker 설정

`mail-worker/src/main/resources/application.yml`
```yaml
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        concurrency: ${RABBITMQ_CONCURRENCY:8}
        max-concurrency: ${RABBITMQ_MAX_CONCURRENCY:16}
        prefetch: ${RABBITMQ_PREFETCH:10}
        default-requeue-rejected: false
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 2000
          multiplier: 2.0
```

(접속 정보는 `${ENV_VAR:기본값}` 플레이스홀더입니다 — 로컬 개발은 기본값으로 compose 인프라에 붙고, 프로덕션은 환경변수를 주입합니다. 전체 목록은 `.env.example`.)

- `concurrency: 8` (max `16`) — 워커 한 대가 발송 잡을 **8개까지 병렬로** 처리합니다. 발송 경로는 DB 왕복 + SMTP로 I/O 바운드라, 한 번에 한 건씩(기본값 1) 처리하면 워커가 대부분 대기만 합니다. 부하가 몰리면 브로커가 최대 16 소비자까지 늘립니다.
- `prefetch: 10` — 소비자 하나가 한 번에 미리 받아두는 미확인(unacked) 메시지 수. 처리 속도 조절 밸브입니다.
- `retry: max-attempts: 3, initial-interval: 2000, multiplier: 2.0` — 리스너가 예외를 던지면 2초 → 4초 간격으로 총 3회까지 재시도.
- `default-requeue-rejected: false` — 3회 모두 실패하면 큐에 되돌리지(무한루프) 않고 **거부** → DLQ 인자 덕분에 `mail.send.dlq`로 이동.

### 3-8. 진행 조회 부가 API — 집계 발송 로그와 드릴다운

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

- **비동기 팬아웃으로 O(1) 생성**: 리스트 캠페인은 `create()`가 수신자를 펼치지 않고 팬아웃 잡 1건만 발행하므로, 응답 시간이 리스트 크기와 무관합니다(100만 행이어도 즉시 201). 무거운 N행 확장은 워커가 배치로 처리하고, `QUEUED→EXPANDING` 클레임이 재배달 시 중복 생성을 막습니다(3-6). 애드혹 `recipients[]`는 요청 크기로 유계라 인라인 확장이 안전합니다.
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

"내일 아침 9시에 보내줘"를 지원합니다. 핵심 아이디어는 **새 파이프라인을 만들지 않는 것**: 예약 캠페인도 생성 시점에 캠페인 행을 저장하되(애드혹이면 PENDING 메시지 행까지) 오직 **RabbitMQ 발행 한 단계만 보류**합니다. 시각이 되면 worker의 스케줄러가 보류된 발행을 대신 수행하고, 그 이후는 즉시 발송과 완전히 동일한 흐름(3-5의 리스너 → dispatchOne)을 탑니다.

리스트 캠페인은 즉시 발송과 마찬가지로 생성 시점에 수신자를 펼치지 않습니다 — 스케줄러가 시각 도래 시 발행하는 것도 발송 잡이 아니라 **팬아웃 잡**이고, 그때부터 3-6의 확장이 도는 것만 다릅니다(아래 6-3 참고). 애드혹 캠페인은 생성 시 PENDING 행을 저장해 두고 발송 잡 발행만 보류합니다.

"보류됨/릴리스됨"의 판별자는 `campaigns.enqueued_at` 컬럼 하나입니다:

- 즉시 발송: 생성 시 `enqueuedAt = now` 스탬프 + 바로 발행.
- 예약 발송: `enqueuedAt = NULL`로 저장, 발행 없음. 스케줄러가 시각 도래 시 **`enqueued_at IS NULL` 조건부 UPDATE**로 스탬프를 찍는 데 성공한(claim을 이긴) 워커만 발행합니다 — 워커가 여러 대여도 정확히 1회.

### 6-2. 흐름

```
 브라우저        mail-api (CampaignService)          Postgres                mail-worker (10초 주기)
    │ POST /api/campaigns        │                      │                           │
    │  { scheduledAt: 미래 }     │                      │                           │
    ├───────────────────────────>│ campaign 저장 ───────>│ enqueued_at = NULL        │
    │                            │ (애드혹이면 MailMessage 저장, 전부 PENDING)         │
    │  201 (RabbitMQ 발행 없음!) │                      │                           │
    │<───────────────────────────┤                      │                           │
    │                                                   │      ScheduledCampaignReleaser (@Scheduled)
    │                                                   │<── ① findDueForEnqueue(now) ──┤ 시각 도래 + 미릴리스 조회
    │                                                   │<── ② claimForEnqueue(id) ─────┤ 조건부 UPDATE (1행 or 0행)
    │                                                   │<── ③ claim을 이긴 워커만 발행: ┤
    │                                                   │      · 리스트 → enqueueFanout ──> RabbitMQ ──> CampaignFanoutListener → 3-6
    │                                                   │      · 애드혹 → PENDING id마다 enqueue ──> RabbitMQ ──> MailSendListener
    │                                                   │       (여기부터는 즉시 발송과 동일)
```

### 6-3. 단계별 실제 코드

**접수 측**은 3-1에서 이미 봤습니다 — `deferred`면 `enqueuedAt`을 `null`로 두고 마지막 발행 단계(리스트면 `enqueueFanout`, 애드혹이면 `enqueue` 루프)를 건너뜁니다.

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
            if (campaign.getListId() != null) {
                // List campaign: recipients were never expanded at create time — hand
                // the fan-out job to the worker now that it is due.
                mailQueue.enqueueFanout(campaign.getId());
                released++;
                log.info("released scheduled list campaign {} (fan-out) scheduledAt={}",
                        campaign.getId(), campaign.getScheduledAt());
            } else {
                List<Long> pendingIds = messages.findPendingIdsByCampaign(campaign.getId());
                pendingIds.forEach(mailQueue::enqueue);
                released++;
                log.info("released scheduled campaign {} ({} messages) scheduledAt={}",
                        campaign.getId(), pendingIds.size(), campaign.getScheduledAt());
            }
        }
        return released;
    }
```

조회는 느슨하게(여러 워커가 같은 캠페인을 볼 수 있음), 결정은 엄격하게(claim에 이긴 하나만 발행). claim을 이긴 뒤에는 캠페인 종류에 따라 갈립니다: **리스트 캠페인**이면 생성 시 수신자를 펼치지 않았으므로 이제 **팬아웃 잡**을 발행해 워커가 확장하게 하고(3-6), **애드혹 캠페인**이면 생성 시 저장해 둔 PENDING 행의 id를 뽑아 발송 잡을 발행합니다. `findPendingIdsByCampaign`은 id만 뽑아옵니다 — 발행에 필요한 건 id뿐이니까요(3-2의 "큐에는 id만" 원칙 그대로).

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
