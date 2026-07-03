# 06. 템플릿과 개인화 (M3)

## 1. 개요 — 무엇을, 왜

캠페인을 만들 때마다 제목/본문을 손으로 써 넣는 대신, **재사용 가능한 템플릿**을 한 번 만들어 두고
여러 발송에서 꺼내 쓰는 기능입니다. 템플릿의 제목·본문에는 `{{firstName}}`, `{{email}}` 같은
**`{{변수}}` 플레이스홀더**를 넣을 수 있고, 실제 발송 시점에 수신자별 값으로 치환됩니다.
"모든 수신자에게 같은 메일"에서 "수신자마다 다른 메일(개인화)"로 넘어가는 단계입니다.

템플릿이 소비되는 경로는 세 가지입니다.

| 경로 | 렌더 시점 | 용도 |
|---|---|---|
| 캠페인 (`templateId`) | 발송 시점 (worker) | 대량 발송 + 수신자별 개인화 |
| 트랜잭셔널 단건 발송 | 생성 즉시 (API) | 환영 메일, 영수증 등 1건 발송 |
| 미리보기 (`preview`) | 즉시, 저장 없음 | 작성 중 결과 확인 |

## 2. 흐름

```
[템플릿 CRUD]
POST/PUT/GET/DELETE /api/templates ──> TemplateService ──> TemplateRepository(Postgres)

[캠페인 경로: 스냅샷 → 발송 시점 렌더]
POST /api/campaigns {templateId, listId}
        │
        ▼
CampaignService.create ── 템플릿 subject/body를 캠페인에 "스냅샷" (아직 {{변수}} 그대로)
        │  fan-out: 수신자별 MailMessage(PENDING, contactId) + RabbitMQ enqueue
        ▼
(worker) MailDispatchService.dispatchOne
        │  contactId → Contact.toVariables() → TemplateRenderer.render (여기서 치환!)
        ▼
트래킹 링크 재작성 + 수신거부 푸터 + 오픈픽셀 → SMTP 발송

[트랜잭셔널 경로: 즉시 렌더 → 1건짜리 캠페인]
POST /api/transactional {templateId, recipient, variables}
        │
        ▼
TransactionalService.send ── 지금 바로 render → 수신자 1명짜리 캠페인으로 큐에 태움
```

## 3. 단계별 코드

### 3-1. 도메인: Template — 순수 POJO

`mail-core/src/main/java/io/github/ahrimjang/mail/core/domain/Template.java`
```java
/**
 * Domain model of a reusable mail template. Subject and body may contain
 * {@code {{variable}}} placeholders rendered per contact at send time.
 * Pure POJO — no JPA / framework concerns.
 */
public class Template {

    private Long id;
    private String name;
    private String subject;
    private String htmlBody;
    private Instant createdAt;
    private Instant updatedAt;
```

JPA 어노테이션이 전혀 없는 순수 자바 객체입니다. DB 매핑은 infra의 `TemplateEntity`가 따로 맡습니다(헥사고날의 기본 규칙).

### 3-2. 렌더러: `{{변수}}` 치환기

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/TemplateRenderer.java`
```java
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}");

    /**
     * Replace every {@code {{name}}} placeholder in {@code text} with the
     * matching value from {@code vars}; missing or null values become "".
     */
    public String render(String text, Map<String, String> vars) {
        if (text == null) {
            return "";
        }
        Matcher matcher = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String v = vars.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v));
        }
        matcher.appendTail(out);
        return out.toString();
    }
```

정규식 `\{\{\s*([이름])\s*\}\}`으로 플레이스홀더를 찾아 map에서 값을 꺼내 바꿉니다.
핵심 규칙 두 가지: **모르는 변수는 빈 문자열**이 되고(발송이 죽지 않음), 값에 `$` 같은
정규식 특수문자가 있어도 `Matcher.quoteReplacement`로 안전하게 그대로 들어갑니다.

### 3-3. TemplateService — CRUD + 미리보기

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/TemplateService.java`
```java
    public TemplateView create(TemplateRequest request) {
        validate(request);
        Template saved = templates.save(Template.create(request.name(), request.subject(), request.htmlBody()));
        return toView(saved);
    }
```

```java
    /** Render the template's subject and body with the given variables, without persisting. */
    public RenderedTemplate preview(Long id, Map<String, String> vars) {
        Template template = load(id);
        return new RenderedTemplate(
                renderer.render(template.getSubject(), vars),
                renderer.render(template.getHtmlBody(), vars)
        );
    }
```

`preview`는 렌더 결과만 돌려주고 **아무것도 저장하거나 발송하지 않습니다.** 프론트의 "미리보기" 버튼이 이걸 씁니다.

### 3-4. API: TemplateController

`mail-api/src/main/java/io/github/ahrimjang/mail/api/TemplateController.java`
```java
@RestController
@RequestMapping("/api/templates")
public class TemplateController {
```

```java
    /** Render the template's subject/body with the given variables without sending anything. */
    @PostMapping("/{id}/preview")
    public RenderedTemplate preview(@PathVariable Long id, @RequestBody Map<String, String> variables) {
        return templates.preview(id, variables);
    }
```

`GET/POST /api/templates`, `GET/PUT/DELETE /api/templates/{id}`, `POST /{id}/preview`가 전부입니다.
`permitAll` 목록에 없으므로 **JWT 필요**.

### 3-5. 캠페인: 템플릿 "스냅샷"

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignService.java`
```java
        String subject;
        String body;
        if (request.templateId() != null) {
            Template template = templates.findById(request.templateId())
                    .orElseThrow(() -> new NoSuchElementException("template not found: " + request.templateId()));
            subject = template.getSubject();
            body = template.getHtmlBody();
        } else {
            subject = request.subject();
            body = request.body();
        }
```

`templateId`가 오면 그 시점의 subject/body를 **복사해서 캠페인 행에 저장**합니다(스냅샷).
이후 템플릿을 수정해도 이미 만든 캠페인은 변하지 않습니다. 단, `{{변수}}`는 아직 치환하지 않고
**그대로 캠페인 본문에 남겨 둡니다** — 치환은 수신자를 아는 발송 시점의 일입니다.

### 3-6. 발송 시점 개인화: dispatchOne

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
```

worker가 메시지 1건을 처리할 때: 기본 변수는 `email` 하나뿐이지만, 메시지에 `contactId`가
연결돼 있으면(리스트 타깃 캠페인 — 07 문서 참고) 그 연락처의 이름·커스텀 속성 전체가 변수 map이 됩니다.
렌더 **후에** 트래킹 재작성이 오는 순서도 중요합니다 — `{{변수}}`가 URL 안에 들어 있어도 먼저 완성된 링크가 되고 나서 클릭 추적으로 감싸집니다.

### 3-7. 트랜잭셔널 단건 발송: 즉시 렌더 + 파이프라인 재사용

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/TransactionalService.java`
```java
    public Long send(TransactionalRequest request) {
        if (request.recipient() == null || !request.recipient().contains("@")) {
            throw new IllegalArgumentException("valid recipient is required");
        }
        Template template = templates.findById(request.templateId())
                .orElseThrow(() -> new NoSuchElementException("template not found: " + request.templateId()));
        Map<String, String> vars = request.variables() == null ? Map.of() : request.variables();

        Campaign campaign = Campaign.draft(
                renderer.render(template.getSubject(), vars),
                renderer.render(template.getHtmlBody(), vars));
        campaign.setStatus(CampaignStatus.QUEUED);
        Campaign saved = campaigns.save(campaign);

        List<MailMessage> savedMessages = messages.saveAll(
                List.of(MailMessage.queued(saved.getId(), request.recipient())));
        savedMessages.forEach(m -> mailQueue.enqueue(m.getId()));

        return saved.getId();
    }
```

캠페인과 달리 **요청에 담긴 variables로 지금 바로 렌더**한 뒤, 그 결과를
"수신자 1명짜리 캠페인"으로 포장해 같은 큐에 넣습니다. 별도 발송 경로를 만들지 않아서
트래킹·수신거부·억제·지표가 전부 공짜로 따라옵니다.

`mail-api/src/main/java/io/github/ahrimjang/mail/api/TransactionalController.java`
```java
    /** Render and enqueue the mail, then return the backing single-recipient campaign. */
    @PostMapping
    public ResponseEntity<CampaignView> send(@RequestBody TransactionalRequest request) {
        Long id = transactional.send(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.get(id));
    }
```

## 4. 설계 포인트 — 왜 이렇게

- **스냅샷 vs 발송 시점 렌더의 분리.** "어떤 내용을 보낼지"는 캠페인 생성 시 고정(스냅샷)하고,
  "누구에게 어떻게 보일지"는 발송 시점에 결정(렌더)합니다. 스냅샷 덕에 템플릿을 나중에 고쳐도
  진행 중인 캠페인이 흔들리지 않고, 발송 시점 렌더 덕에 수신자 10만 명이어도 본문을 10만 벌 미리 저장할 필요가 없습니다.
- **모르는 변수 = 빈 문자열.** 오타 하나로 대량 발송이 통째로 실패하는 것보다, 빈칸으로 나가는 쪽을 택한 MVP 트레이드오프입니다.
- **트랜잭셔널은 즉시 렌더.** 요청자가 변수를 직접 주므로 기다릴 이유가 없고, 렌더 결과가 캠페인 스냅샷에 남아 "무엇이 나갔는지"가 기록됩니다.
- **렌더러는 core의 순수 컴포넌트.** 정규식 치환뿐이라 프레임워크 의존이 없고, 나중에 Handlebars/Liquid 등으로 교체해도 호출부는 그대로입니다.

## 5. 확인 방법

JWT 필요 — 먼저 토큰 발급:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@test.com","password":"secret1"}' | jq -r .token)
```

```bash
# 1) 템플릿 생성
curl -s -X POST http://localhost:8080/api/templates \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"welcome","subject":"{{firstName}}님 환영합니다",
       "htmlBody":"<p>안녕하세요 {{firstName}}님! <a href=\"https://example.com\">시작하기</a></p>"}'

# 2) 미리보기 (발송 없음, 저장 없음)
curl -s -X POST http://localhost:8080/api/templates/1/preview \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"firstName":"아림"}'
# → {"subject":"아림님 환영합니다", "htmlBody":"<p>안녕하세요 아림님! ..."}

# 3) 트랜잭셔널 단건 발송
curl -s -X POST http://localhost:8080/api/transactional \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"templateId":1,"recipient":"a@test.com","variables":{"firstName":"아림"}}'

# 4) 템플릿 기반 캠페인 (스냅샷 → worker가 발송 시점 렌더)
curl -s -X POST http://localhost:8080/api/campaigns \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"templateId":1,"recipients":["b@test.com"]}'
```

MailHog(`http://localhost:8025`)에서 실제 도착한 메일의 제목/본문에 변수가 치환됐는지,
직접 수신자(recipients) 캠페인은 contact가 없어 `{{firstName}}`이 빈칸으로 나가는지 비교해 보세요.
