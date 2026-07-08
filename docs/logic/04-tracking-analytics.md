# 04. 오픈/클릭 트래킹과 분석 지표

## 1. 개요 — 무엇을, 왜

수신자가 메일을 **열었는지(open)**, 본문 링크를 **눌렀는지(click)** 를 외부 서비스 없이
직접(self-implemented) 추적하는 기능입니다. 원리는 업계 표준 그대로입니다:

- **오픈 추적**: 메일 본문 끝에 눈에 안 보이는 1×1 픽셀 이미지(`<img>`)를 넣는다.
  수신자의 메일 클라이언트가 이 이미지를 로딩하는 순간 = "메일을 열었다"는 신호가
  우리 서버(`/api/track/open/{token}`)로 들어온다.
- **클릭 추적**: 본문의 모든 링크를 우리 서버를 경유하는 리다이렉트 URL로 바꿔치기한다.
  수신자가 링크를 누르면 먼저 `/api/track/click/{token}?u=원본URL`에 도착 → 서버가
  CLICK을 기록하고 → 302로 원래 URL로 보내준다. 수신자 입장에선 그냥 링크가 열린 것.

"누가" 열었는지 알려면 **수신자(메시지)마다 다른 URL**이 필요합니다. 그래서 메시지 한 건마다
무작위 `trackingToken`(UUID)을 발급해 URL에 심습니다. 토큰만 보고는 이메일 주소를 알 수
없으므로(추측 불가) 인증 없는 공개 엔드포인트로 둘 수 있습니다.

핵심 설계 원칙 하나: **오픈/클릭은 메시지 status가 아니라 별도의 이벤트(EmailEvent)로
기록**합니다. status는 배달 결과(SENT/BOUNCED...)만 담고, 참여(engagement)는 이벤트에서
집계로 파생시킵니다. 한 사람이 메일을 3번 열 수도 있으니 상태 하나로는 표현이 안 되기 때문입니다.

그리고 그 이벤트는 이제 **DB에 직접 쓰지 않고 Kafka 이벤트 스트림을 탑니다.** 추적 엔드포인트
(mail-api)는 `EmailEventPublisher` 포트로 이벤트를 `mail.events` 토픽에 **발행(fire-and-forget)**
하고 즉시 응답하며, mail-worker의 `@KafkaListener`가 그 스트림을 구독해 `email_events` 테이블
(읽기 모델)로 **프로젝션**합니다. 지표 조회는 그 읽기 모델을 셉니다. 반면 배달 상태
(SENT/BOUNCED, 억제)는 여전히 동기적으로 Postgres에 기록됩니다 — **비동기로 넘어간 것은
참여 "사실(fact)"뿐**입니다.

## 2. 흐름

```
[발송 시 — mail-worker]
CampaignService.create ──▶ MailMessage 생성 (trackingToken = UUID)
MailDispatchService.dispatchOne
  ├─ TrackingRewriter.rewriteLinks(body)   href="https://a.com"
  │      → href="{base}/api/track/click/{token}?u=https%3A%2F%2Fa.com"
  └─ TrackingRewriter.openPixel()          본문 끝에 <img src=".../track/open/{token}">

[수신자 행동 — mail-api: 발행만 하고 즉시 응답]
메일 열람 ──▶ GET /api/track/open/{token} ──▶ TrackingService.recordOpen
                                                └─ EmailEvent(OPEN) 발행, 1x1 GIF 응답
링크 클릭 ──▶ GET /api/track/click/{token}?u=... ──▶ TrackingService.recordClick
                                                └─ EmailEvent(CLICK) 발행, 302 → 원본 URL
        EmailEventPublisher 포트 ──▶ KafkaEmailEventPublisher
                                        └─ Kafka 토픽 "mail.events" (JSON EmailEventMessage,
                                                                     key = campaignId)

[프로젝션 — mail-worker: 스트림 → 읽기 모델]
@KafkaListener EmailEventProjectionListener.onEvent
  └─ email_events 테이블에 append (at-least-once, 중복 허용)

[지표 조회 — mail-api]
GET /api/campaigns/{id} ──▶ CampaignService.toView
  └─ events.countDistinctMessages(campaignId, OPEN/CLICK)  → opened / clicked
```

## 3. 단계별 실제 코드

### 3-1. 토큰 발급 — 메시지 생성 시점에 UUID를 심는다

캠페인 생성 시 수신자 1명당 `MailMessage` 한 행이 만들어지는데, 이때 이미 토큰이 생깁니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/domain/MailMessage.java`
```java
    /** Factory for a newly enqueued, not-yet-sent message linked to a contact for personalization. */
    public static MailMessage queued(Long campaignId, String recipient, Long contactId) {
        MailMessage m = new MailMessage();
        m.campaignId = campaignId;
        m.recipient = recipient;
        m.status = MessageStatus.PENDING;
        m.attempts = 0;
        m.unsubToken = java.util.UUID.randomUUID().toString();
        m.trackingToken = java.util.UUID.randomUUID().toString();
        m.contactId = contactId;
        m.updatedAt = Instant.now();
        return m;
    }
```

### 3-2. 링크 재작성 + 오픈 픽셀 — TrackingRewriter (순수 문자열 가공)

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/TrackingRewriter.java`
```java
    private static final Pattern HREF = Pattern.compile("href=\"(https?://[^\"]+)\"");

    /**
     * Rewrite every {@code href} pointing at an http(s) URL to route through the
     * click-tracking endpoint. Non-http hrefs are left untouched.
     */
    public String rewriteLinks(String html, String trackingToken, String baseUrl) {
        Matcher matcher = HREF.matcher(html);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1);
            String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
            String replacement = "href=\"" + baseUrl + "/api/track/click/" + trackingToken + "?u=" + encoded + "\"";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Build the hidden 1x1 open-tracking pixel for the given token. */
    public String openPixel(String trackingToken, String baseUrl) {
        return "<img src=\"" + baseUrl + "/api/track/open/" + trackingToken +
                "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none\"/>";
    }
```

원본 URL은 `URLEncoder.encode`로 쿼리 파라미터 `u`에 안전하게 담습니다(URL 안에 `&`, `?`가
있어도 깨지지 않게). 정규식이 `href="http..."` 큰따옴표 형태만 잡는 것은 알려진 MVP 한계입니다.

### 3-3. 발송 직전 본문 조립 — 워커가 재작성기를 호출하는 위치

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
        String trackedBody = trackingRewriter.rewriteLinks(bodySrc, message.getTrackingToken(), baseUrl);
        String html = trackedBody + unsubscribeFooter(message.getUnsubToken())
                + trackingRewriter.openPixel(message.getTrackingToken(), baseUrl);
```

순서가 중요합니다: **링크 재작성 → 수신거부 푸터 → 오픈 픽셀**. 링크 재작성을 먼저 하기
때문에 푸터의 수신거부 링크는 클릭 추적 대상이 되지 않습니다(수신거부를 클릭 지표로 세면 안 되니까).

### 3-4. 공개 엔드포인트 — TrackingController

`mail-api/src/main/java/io/github/ahrimjang/mail/api/TrackingController.java`
```java
    private static final byte[] PIXEL =
            Base64.getDecoder().decode("R0lGODlhAQABAID/AP///wAAACwAAAAAAQABAAACAkQBADs=");

    private final TrackingService tracking;

    public TrackingController(TrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping(value = "/api/track/open/{token}", produces = MediaType.IMAGE_GIF_VALUE)
    public ResponseEntity<byte[]> open(@PathVariable String token) {
        tracking.recordOpen(token);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_GIF).body(PIXEL);
    }

    @GetMapping("/api/track/click/{token}")
    public ResponseEntity<Void> click(@PathVariable String token, @RequestParam("u") String url) {
        tracking.recordClick(token, url);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
```

- `PIXEL`은 43바이트짜리 투명 1×1 GIF를 Base64로 하드코딩해 둔 것 — 파일 서빙조차 필요 없습니다.
- 클릭은 `HttpStatus.FOUND`(302) + `Location: 원본URL`로 응답 → 브라우저가 즉시 원래 목적지로 이동.
- 이 경로들은 `SecurityConfig`에서 `/api/track/**` permitAll — 수신자는 로그인한 사용자가 아니므로.

### 3-5. 이벤트 발행 — TrackingService (토큰 → 메시지 역해석 → publish)

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/TrackingService.java`
```java
    public void recordOpen(String trackingToken) {
        messages.findByTrackingToken(trackingToken)
                .ifPresent(m -> events.publish(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.OPEN, null)));
    }

    public void recordClick(String trackingToken, String url) {
        messages.findByTrackingToken(trackingToken)
                .ifPresent(m -> events.publish(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.CLICK, url)));
    }
```

토큰으로 메시지를 찾고, 찾으면 이벤트를 **스트림에 발행**합니다 — `events`는 저장소가 아니라
`EmailEventPublisher` 포트라서, 추적 엔드포인트는 이벤트 테이블에 손대지 않고 빠르게 응답합니다.
**모르는 토큰은 조용히 무시**(`ifPresent`) — 봇이나 스캐너가 아무 토큰이나 찔러도 에러도
데이터도 안 생깁니다.

발행 포트는 core에 있고, 저장 포트(`EmailEventRepository`)와 일부러 분리되어 있습니다 —
쓰는 쪽(발행)과 읽는 쪽(집계)이 서로 다른 프로세스이기 때문입니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/EmailEventPublisher.java`
```java
public interface EmailEventPublisher {

    /** Publish one engagement event to the stream. */
    void publish(EmailEvent event);
}
```

참고: 반송 웹훅을 처리하는 `BounceService`도 같은 포트로 `EmailEvent(BOUNCE)`를 발행합니다
(05 문서) — 오픈/클릭/반송 세 가지 참여 사실이 전부 같은 스트림을 탑니다.

### 3-6. Kafka 어댑터 — mail.events 토픽으로 발행

포트의 실제 구현은 infra의 Kafka 어댑터입니다. 도메인 객체를 전송용 DTO(`EmailEventMessage`)로
바꿔 `mail.events` 토픽에 JSON으로 쏩니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/messaging/KafkaEmailEventPublisher.java`
```java
@Component
public class KafkaEmailEventPublisher implements EmailEventPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public KafkaEmailEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(EmailEvent e) {
        EmailEventMessage message = new EmailEventMessage(
                e.getMessageId(), e.getCampaignId(), e.getType(), e.getUrl(),
                e.getOccurredAt().toEpochMilli());
        kafkaTemplate.send(KafkaEventConfig.TOPIC, String.valueOf(e.getCampaignId()), message);
    }
}
```

**키가 `campaignId`** 인 것이 포인트: 같은 캠페인의 이벤트는 같은 파티션에 들어가 순서가
보존됩니다(파티션을 늘려도 캠페인 단위 순서는 유지). 토픽 자체는 설정 클래스가 선언합니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/messaging/KafkaEventConfig.java`
```java
@Configuration
public class KafkaEventConfig {

    public static final String TOPIC = "mail.events";
    public static final String PROJECTION_GROUP = "mail-worker-projection";

    @Bean
    public NewTopic mailEventsTopic() {
        // Single partition/replica is a dev sizing; keyed by campaignId either way
        // so per-campaign ordering survives a future partition increase.
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }
}
```

전송 페이로드는 `mail-common`의 record 하나입니다 — 시각을 `Instant`가 아닌 epoch millis로
실어서 양쪽 어디에도 JavaTime 직렬화 설정이 필요 없게 했습니다.

`mail-common/src/main/java/io/github/ahrimjang/mail/common/EmailEventMessage.java`
```java
public record EmailEventMessage(
        Long messageId,
        Long campaignId,
        EventType type,
        String url,
        long occurredAtEpochMilli) {
}
```

### 3-7. 프로젝션 — 스트림을 읽기 모델로 (mail-worker)

스트림의 반대쪽 끝. worker의 Kafka 리스너가 이벤트를 받아 `email_events` 테이블에
append합니다. 지표 집계가 읽는 것은 Kafka가 아니라 이 테이블입니다.

`mail-worker/src/main/java/io/github/ahrimjang/mail/worker/EmailEventProjectionListener.java`
```java
@Component
public class EmailEventProjectionListener {

    private final EmailEventRepository events;

    public EmailEventProjectionListener(EmailEventRepository events) {
        this.events = events;
    }

    @KafkaListener(topics = KafkaEventConfig.TOPIC, groupId = KafkaEventConfig.PROJECTION_GROUP)
    public void onEvent(EmailEventMessage message) {
        EmailEvent event = EmailEvent.of(message.messageId(), message.campaignId(), message.type(), message.url());
        event.setOccurredAt(Instant.ofEpochMilli(message.occurredAtEpochMilli()));
        events.save(event);
    }
}
```

`occurredAt`을 메시지에 실려 온 값으로 **되돌려 덮어쓰는** 것에 주목 — 이벤트 발생 시각은
발행 시점(수신자가 실제로 연 순간)이지, 프로젝션이 소비한 시점이 아니기 때문입니다.
Kafka 소비는 at-least-once라 재배달 시 같은 이벤트가 두 행 쌓일 수 있지만, 프로젝션은
append-only고 아래 3-9의 집계가 `distinct messageId`라서 **중복 행은 지표를 왜곡하지
않습니다** — 멱등 처리를 따로 구현하지 않고 집계 방식으로 흡수한 것입니다.

### 3-8. 이벤트 도메인 — EmailEvent

`mail-core/src/main/java/io/github/ahrimjang/mail/core/domain/EmailEvent.java`
```java
    /** Factory for a newly observed engagement event. */
    public static EmailEvent of(Long messageId, Long campaignId, EventType type, String url) {
        EmailEvent e = new EmailEvent();
        e.messageId = messageId;
        e.campaignId = campaignId;
        e.type = type;
        e.url = url;
        e.occurredAt = Instant.now();
        return e;
    }
```

`campaignId`를 이벤트에 **비정규화(중복 저장)** 해 둔 덕에, 캠페인 지표를 집계할 때 메시지
테이블과 조인할 필요가 없습니다. `url`은 CLICK일 때만 채워집니다(OPEN은 null).

### 3-9. distinct 집계 — 열람 "횟수"가 아니라 "사람 수"

같은 수신자가 메일을 다섯 번 열면 OPEN 이벤트가 5행 쌓입니다. 지표로 원하는 건
"몇 통이 열렸나"이므로 **`distinct messageId`로 셉니다.**

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/EmailEventJpaRepository.java`
```java
public interface EmailEventJpaRepository extends JpaRepository<EmailEventEntity, Long> {

    @Query("select count(distinct e.messageId) from EmailEventEntity e where e.campaignId = ?1 and e.type = ?2")
    long countDistinctMessages(Long campaignId, EventType type);
}
```

### 3-10. 조회 응답에 합류 — CampaignService.toView

프론트가 폴링하는 `GET /api/campaigns/{id}` 응답을 만드는 곳입니다. 배달 카운트(status 기반)와
참여 카운트(이벤트 기반)가 여기서 하나의 `CampaignView`로 합쳐집니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignService.java`
```java
    private CampaignView toView(Campaign campaign) {
        MessageCounts counts = messages.countByCampaign(campaign.getId());
        long opened = events.countDistinctMessages(campaign.getId(), EventType.OPEN);
        long clicked = events.countDistinctMessages(campaign.getId(), EventType.CLICK);
        return new CampaignView(
                campaign.getId(),
                campaign.getSubject(),
                campaign.getStatus(),
                counts.total(),
                counts.pending(),
                counts.sent(),
                counts.failed(),
                counts.bounced(),
                counts.suppressed(),
                opened,
                clicked,
                campaign.getCreatedAt(),
                campaign.getSenderName(),
                campaign.getSenderEmail(),
                campaign.getScheduledAt()
        );
    }
```

`opened`/`clicked`는 프로젝션이 이미 소비한 이벤트까지만 반영됩니다 — 발행에서 집계 반영까지
스트림을 한 번 도는 짧은 지연이 있지만(최종 일관성), 프론트가 어차피 폴링으로 갱신하므로
다음 폴링 주기 안에 따라잡힙니다.

## 4. 설계 포인트 — 왜 이렇게

- **status와 이벤트의 분리.** 배달 결과는 메시지당 정확히 하나(상태 머신)지만, 참여는
  0~N번 일어나는 사실(fact)입니다. 이벤트를 append-only로 쌓고 조회 시 집계하면
  "재열람", "여러 링크 클릭", 향후 "시간대별 오픈 그래프"까지 데이터 손실 없이 지원됩니다.
- **발송 상태는 동기, 참여 이벤트만 비동기.** SENT/BOUNCED와 억제 등록은 발송 흐름 안에서
  즉시 Postgres에 커밋됩니다 — 틀리면 이중 발송 같은 실질적 피해가 나는 "제어" 데이터라서요.
  반면 오픈/클릭은 잠깐 늦게 반영돼도 무해한 "관측" 데이터라 스트림으로 뺐습니다. 그 대가로
  추적 엔드포인트는 DB 쓰기 없이(fire-and-forget 발행) 수신자 트래픽 폭주에도 가볍게 응답합니다.
- **RabbitMQ는 작업큐, Kafka는 이벤트 로그.** 같은 "비동기"라도 성격이 다릅니다: 발송 잡은
  한 번 소비되고 ack/재시도/DLQ로 관리되는 **작업**(RabbitMQ), 참여 이벤트는 여러 소비자가
  각자의 오프셋으로 재생(replay)할 수 있는 **사실의 로그**(Kafka)입니다. 지금은 프로젝션
  소비자 하나지만, 같은 토픽에 실시간 대시보드·세그먼테이션 소비자를 붙여도 발행 쪽은 무변경.
- **at-least-once + append-only + distinct 집계.** Kafka 소비는 중복 배달될 수 있고 프로젝션은
  멱등 키 없이 그냥 append합니다 — 대신 지표가 `distinct messageId`라 중복 행이 숫자를 못
  건드립니다. "중복 제거"를 인프라에서 힘겹게 보장하는 대신 집계 층에서 흡수한 선택입니다.
- **메시지당 토큰 = 식별자이자 인증.** URL에 이메일이나 id를 노출하지 않고, UUID라 열거
  공격(다른 사람 토큰 추측)이 사실상 불가능합니다. 그래서 permitAll로 열어도 안전합니다.
- **TrackingRewriter는 순수 컴포넌트.** HTTP도 DB도 모르는 문자열 함수라서 mail-core에
  두고 단위 테스트하기 쉽습니다. 어디서 호출할지는 워커(MailDispatchService)의 책임.
- **집계는 저장하지 않고 매번 계산.** POC 규모에서는 읽기 모델에 대한 count 쿼리로 충분하고,
  캐시/스냅샷 없이 항상 정확합니다. 쓰기 쪽은 이미 스트림(발행→프로젝션)으로 분리되어 있으니,
  규모가 커지면 이 count 지점만 집계 테이블(스트림에서 증분 갱신)로 바꾸면 되는 seam입니다.
- **알려진 한계**: (1) 정규식 링크 재작성은 `href='...'`(홑따옴표) 등을 놓침,
  (2) 오픈 픽셀은 이미지 차단(Gmail 프록시, 기업 메일) 시 못 잡음 — 오픈율은 원래 과소집계 경향,
  (3) `u` 파라미터 리다이렉트는 open-redirect가 될 수 있어 운영 전 서명/화이트리스트 필요.

## 5. 확인 방법

docker compose 인프라(Postgres + RabbitMQ + **Kafka** + MailHog) + mail-api + mail-worker + frontend를
띄운 뒤 진행합니다. **worker가 꺼져 있으면 오픈/클릭을 아무리 찍어도 지표가 0에 머뭅니다** —
이벤트는 토픽에 쌓이기만 하고 프로젝션이 안 돌기 때문인데, worker를 다시 켜면 밀린 이벤트가
소비되며 지표가 따라잡습니다(이게 이벤트 로그 방식의 장점이기도 합니다).

```bash
# 1) 로그인 토큰 받기 (가입은 /api/auth/signup)
TOKEN=$(curl -s localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"me@test.com","password":"pw1234"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

# 2) 링크가 든 캠페인 발송
curl -s localhost:8080/api/campaigns -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"subject":"track test","body":"<p><a href=\"https://example.com\">visit</a></p>","recipients":["a@x.com"]}'

# 3) http://localhost:8025 (MailHog) 에서 메일 소스 확인:
#    - href 가 /api/track/click/{token}?u=... 로 바뀌어 있음
#    - 본문 끝에 <img src=".../api/track/open/{token}" ...> 픽셀 존재

# 4) 토큰을 복사해 수신자 행동 시뮬레이션
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/track/open/<token>     # 200 (GIF)
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" \
  "localhost:8080/api/track/click/<token>?u=https%3A%2F%2Fexample.com"             # 302 https://example.com

# 5) 지표 반영 확인 — opened/clicked 가 1로 (여러 번 호출해도 distinct 라 1 유지)
#    발행→프로젝션 한 바퀴를 도는 짧은 지연이 있을 수 있음
curl -s localhost:8080/api/campaigns/1 -H "Authorization: Bearer $TOKEN"

# 6) (선택) 스트림 자체를 눈으로 확인 — mail.events 토픽에 JSON 이벤트가 쌓이는지
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic mail.events --from-beginning
# → {"messageId":1,"campaignId":1,"type":"OPEN","url":null,"occurredAtEpochMilli":...}

# 7) (선택) 읽기 모델 확인
psql -h localhost -U maildb maildb -c "select * from email_events;"
```

화면으로는: 프론트(:5173)에서 캠페인 생성 → MailHog에서 메일을 열고 링크를 클릭 →
캠페인 목록의 opened/clicked 카운트가 폴링으로 올라가는 것을 확인합니다.
