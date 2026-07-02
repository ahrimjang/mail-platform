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

핵심 설계 원칙 하나: **오픈/클릭은 메시지 status가 아니라 별도의 이벤트(EmailEvent) 행으로
기록**합니다. status는 배달 결과(SENT/BOUNCED...)만 담고, 참여(engagement)는 이벤트에서
집계로 파생시킵니다. 한 사람이 메일을 3번 열 수도 있으니 상태 하나로는 표현이 안 되기 때문입니다.

## 2. 흐름

```
[발송 시 — mail-worker]
CampaignService.create ──▶ MailMessage 생성 (trackingToken = UUID)
MailDispatchService.dispatchOne
  ├─ TrackingRewriter.rewriteLinks(body)   href="https://a.com"
  │      → href="{base}/api/track/click/{token}?u=https%3A%2F%2Fa.com"
  └─ TrackingRewriter.openPixel()          본문 끝에 <img src=".../track/open/{token}">

[수신자 행동 — mail-api]
메일 열람 ──▶ GET /api/track/open/{token} ──▶ TrackingService.recordOpen
                                                └─ EmailEvent(OPEN) 저장, 1x1 GIF 응답
링크 클릭 ──▶ GET /api/track/click/{token}?u=... ──▶ TrackingService.recordClick
                                                └─ EmailEvent(CLICK) 저장, 302 → 원본 URL

[지표 조회]
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

### 3-5. 이벤트 기록 — TrackingService (토큰 → 메시지 역해석)

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/TrackingService.java`
```java
    public void recordOpen(String trackingToken) {
        messages.findByTrackingToken(trackingToken)
                .ifPresent(m -> events.save(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.OPEN, null)));
    }

    public void recordClick(String trackingToken, String url) {
        messages.findByTrackingToken(trackingToken)
                .ifPresent(m -> events.save(EmailEvent.of(m.getId(), m.getCampaignId(), EventType.CLICK, url)));
    }
```

토큰으로 메시지를 찾고, 찾으면 이벤트 한 행을 남깁니다. **모르는 토큰은 조용히 무시**
(`ifPresent`) — 봇이나 스캐너가 아무 토큰이나 찔러도 에러도 데이터도 안 생깁니다.

### 3-6. 이벤트 도메인 — EmailEvent

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

### 3-7. distinct 집계 — 열람 "횟수"가 아니라 "사람 수"

같은 수신자가 메일을 다섯 번 열면 OPEN 이벤트가 5행 쌓입니다. 지표로 원하는 건
"몇 통이 열렸나"이므로 **`distinct messageId`로 셉니다.**

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/EmailEventJpaRepository.java`
```java
public interface EmailEventJpaRepository extends JpaRepository<EmailEventEntity, Long> {

    @Query("select count(distinct e.messageId) from EmailEventEntity e where e.campaignId = ?1 and e.type = ?2")
    long countDistinctMessages(Long campaignId, EventType type);
}
```

### 3-8. 조회 응답에 합류 — CampaignService.toView

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
                campaign.getCreatedAt()
        );
    }
```

## 4. 설계 포인트 — 왜 이렇게

- **status와 이벤트의 분리.** 배달 결과는 메시지당 정확히 하나(상태 머신)지만, 참여는
  0~N번 일어나는 사실(fact)입니다. 이벤트를 append-only로 쌓고 조회 시 집계하면
  "재열람", "여러 링크 클릭", 향후 "시간대별 오픈 그래프"까지 데이터 손실 없이 지원됩니다.
- **메시지당 토큰 = 식별자이자 인증.** URL에 이메일이나 id를 노출하지 않고, UUID라 열거
  공격(다른 사람 토큰 추측)이 사실상 불가능합니다. 그래서 permitAll로 열어도 안전합니다.
- **TrackingRewriter는 순수 컴포넌트.** HTTP도 DB도 모르는 문자열 함수라서 mail-core에
  두고 단위 테스트하기 쉽습니다. 어디서 호출할지는 워커(MailDispatchService)의 책임.
- **집계는 저장하지 않고 매번 계산.** POC 규모에서는 count 쿼리로 충분하고, 캐시/스냅샷
  없이 항상 정확합니다. 규모가 커지면 이 지점이 집계 테이블·스트림 처리로 바뀌는 seam입니다.
- **알려진 한계**: (1) 정규식 링크 재작성은 `href='...'`(홑따옴표) 등을 놓침,
  (2) 오픈 픽셀은 이미지 차단(Gmail 프록시, 기업 메일) 시 못 잡음 — 오픈율은 원래 과소집계 경향,
  (3) `u` 파라미터 리다이렉트는 open-redirect가 될 수 있어 운영 전 서명/화이트리스트 필요.

## 5. 확인 방법

MailHog + mail-api + mail-worker + frontend를 띄운 뒤:

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
curl -s localhost:8080/api/campaigns/1 -H "Authorization: Bearer $TOKEN"
```

화면으로는: 프론트(:5173)에서 캠페인 생성 → MailHog에서 메일을 열고 링크를 클릭 →
캠페인 목록의 opened/clicked 카운트가 폴링으로 올라가는 것을 확인합니다.
