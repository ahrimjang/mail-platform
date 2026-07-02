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
 브라우저          mail-api (CampaignService)              H2 DB                RabbitMQ              mail-worker
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

## 3. 단계별 실제 코드

### 3-1. 접수 — CampaignService.create()

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignService.java`
```java
        Campaign campaign = Campaign.draft(subject, body);
        campaign.setStatus(CampaignStatus.QUEUED);
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
        savedMessages.forEach(m -> mailQueue.enqueue(m.getId()));

        return toView(saved);
```

읽는 순서 그대로가 흐름입니다: 캠페인 저장 → 수신자 목록(직접 입력 or 연락처 리스트)을 `MailMessage` 행으로 **팬아웃(fan-out)** → 전부 저장 → **저장된 각 행의 id를 큐에 발행** → 즉시 반환. 발송 코드는 이 파일에 한 줄도 없습니다.

수신자는 두 갈래로 옵니다: `listId`가 있으면 M4 연락처 리스트의 멤버를, 없으면 요청에 직접 담긴 `recipients` 배열을 씁니다. (제목/본문도 마찬가지로 `templateId`가 있으면 템플릿에서 가져옵니다 — 같은 파일의 `create()` 앞부분 참고.)

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
    host: localhost
    port: 5672
    username: guest
    password: guest
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

- `prefetch: 10` — 워커가 한 번에 미리 받아두는 미확인(unacked) 메시지 수. 처리 속도 조절 밸브입니다.
- `retry: max-attempts: 3, initial-interval: 2000, multiplier: 2.0` — 리스너가 예외를 던지면 2초 → 4초 간격으로 총 3회까지 재시도.
- `default-requeue-rejected: false` — 3회 모두 실패하면 큐에 되돌리지(무한루프) 않고 **거부** → DLQ 인자 덕분에 `mail.send.dlq`로 이동.

## 4. 설계 포인트 (왜 이렇게)

- **id만 큐에 태우는 이유**: 본문을 통째로 큐에 실으면 DB와 큐 두 곳에 진실이 생겨 어긋날 수 있습니다. id만 나르면 소비 시점에 DB에서 최신 상태를 읽으므로 항상 일관됩니다. 메시지도 가볍습니다.
- **at-least-once와 멱등성**: RabbitMQ는 "최소 1회 전달"을 보장합니다 — 즉 같은 잡이 **두 번** 올 수 있습니다. 그래서 소비 측 `dispatchOne`이 "이미 PENDING이 아니면 스킵"하는 멱등 설계입니다(03 문서 참고). 큐를 믿는 게 아니라 DB 상태를 믿습니다.
- **DLQ**: 계속 실패하는 잡(포이즌 메시지)이 큐를 막고 무한 재시도되는 것을 막는 표준 패턴. 운영자는 DLQ만 모니터링하면 됩니다.
- **포트 덕분에 브로커 교체 가능**: `CampaignService`는 `MailQueue` 인터페이스만 봅니다. SQS/Kafka로 바꾸려면 `infra`에 어댑터 하나만 새로 쓰면 됩니다.
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
```

눈으로 보는 곳들:

- **RabbitMQ 관리 UI** `http://localhost:15672` (guest/guest) → Queues 탭에서 `mail.send.queue`에 메시지가 쌓였다 빠지는 것, `mail.send.dlq`에 실패분이 남는 것을 볼 수 있습니다.
- **MailHog** `http://localhost:8025` → 실제 도착한 메일 확인.
- **재미있는 실험**: mail-worker를 끈 채 캠페인을 만들면 잡이 큐에 쌓여만 있고(`pending` 그대로), worker를 켜는 순간 소비가 시작됩니다 — "API와 발송이 분리되어 있다"를 몸으로 확인하는 방법입니다.
