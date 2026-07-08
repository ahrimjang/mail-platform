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

작성하는 쪽에는 프론트 **템플릿 에디터 3종**(블록 / 텍스트 / HTML — 3-8절)과
본문에 넣을 **이미지 업로드**(3-9절)가 붙습니다. 어느 에디터로 만들든 백엔드가 저장하는 것은
`name/subject/htmlBody` 세 필드뿐 — 에디터별 편집 상태는 `htmlBody` 앞의 HTML 주석 마커로
함께 실려 다닙니다(발송 파이프라인은 주석을 무시).

## 2. 흐름

```
[템플릿 작성 — 프론트 에디터 3종 (3-8절)]
/editor (블록) · /editor/text (텍스트) · /editor/html (HTML)
        │  htmlBody = (블록/텍스트 에디터라면) <!--opblocks/optext:...--> 마커 + 렌더된 HTML
        │  이미지: POST /api/uploads → 공개 URL을 본문에 삽입 (3-9절)
        ▼
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
        campaign.setEnqueuedAt(java.time.Instant.now()); // transactional sends are always immediate
        Campaign saved = campaigns.save(campaign);

        List<MailMessage> savedMessages = messages.saveAll(
                List.of(MailMessage.queued(saved.getId(), request.recipient())));
        savedMessages.forEach(m -> mailQueue.enqueue(m.getId()));

        return saved.getId();
    }
```

캠페인과 달리 **요청에 담긴 variables로 지금 바로 렌더**한 뒤, 그 결과를
"수신자 1명짜리 캠페인"으로 포장해 같은 큐에 넣습니다. 별도 발송 경로를 만들지 않아서
트래킹·수신거부·억제·지표가 전부 공짜로 따라옵니다. (`enqueuedAt`을 즉시 채우는 것은
예약 발송 기능과의 접점 — 트랜잭셔널은 항상 즉시 발송이라 스케줄러 대상이 아님을 표시합니다.)

`mail-api/src/main/java/io/github/ahrimjang/mail/api/TransactionalController.java`
```java
    /** Render and enqueue the mail, then return the backing single-recipient campaign. */
    @PostMapping
    public ResponseEntity<CampaignView> send(@RequestBody TransactionalRequest request) {
        Long id = transactional.send(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.get(id));
    }
```

### 3-8. 에디터와 마커 — 프론트에서 htmlBody가 만들어지는 방식

템플릿을 만드는 UI는 세 가지이고, 전부 같은 `POST/PUT /api/templates`로 저장합니다.

| 에디터 | 라우트 | 편집 단위 | 저장물 |
|---|---|---|---|
| 블록 에디터 (`pages/EmailEditor.tsx`) | `/editor`, `/editor/:id` | 드래그 가능한 상자(텍스트/이미지/버튼/2단/구분선/푸터) | `<!--opblocks:…-->` 마커 + 이메일 안전 테이블 HTML |
| 텍스트 에디터 (`pages/TextEditor.tsx`) | `/editor/text`, `/editor/text/:id` | 순수 텍스트(빈 줄 = 문단) | `<!--optext:…-->` 마커 + 최소 HTML |
| HTML 에디터 (`pages/HtmlEditor.tsx`) | `/editor/html`, `/editor/html/:id` | HTML 소스 그대로 | 마커 없음 (`htmlBody` = 입력한 코드) |

핵심 트릭은 **재편집 문제**입니다. 백엔드는 `htmlBody` 문자열 하나만 저장하는데, 블록 에디터가
렌더한 테이블 HTML만 저장하면 "상자 구조"가 사라져 다시 열 수 없습니다. 그래서 저장 시
**편집 상태(블록 JSON / 텍스트 원본)를 base64로 접어 HTML 주석 마커로 본문 앞에 붙입니다.**

`frontend/src/outpace/blocks.ts`
```ts
const BLOCKS_PREFIX = "<!--opblocks:";
const TEXT_PREFIX = "<!--optext:";
const MARKER_VERSION = 2;
```

```ts
/** htmlBody for a block template: marker (edit state) + rendered HTML (send content). */
export function blocksToHtmlBody(blocks: Block[]): string {
  const payload = JSON.stringify({ v: MARKER_VERSION, blocks });
  return `${BLOCKS_PREFIX}${b64encode(payload)}-->\n${renderBlocksHtml(blocks)}`;
}

/** Recover blocks from a saved htmlBody; null when it wasn't made by this editor. */
export function parseBlocksMarker(htmlBody: string): Block[] | null {
  if (!htmlBody.startsWith(BLOCKS_PREFIX)) return null;
  const end = htmlBody.indexOf("-->");
  if (end < 0) return null;
  try {
    const parsed = JSON.parse(b64decode(htmlBody.slice(BLOCKS_PREFIX.length, end)));
    if (Array.isArray(parsed)) return (parsed as Block[]).map(upgradeV1); // v1 marker
    if (parsed && parsed.v === MARKER_VERSION && Array.isArray(parsed.blocks)) return parsed.blocks as Block[];
    return null;
  } catch {
    return null;
  }
}
```

발송 파이프라인(렌더러·트래킹 재작성·SMTP)은 HTML 주석을 특별 취급하지 않고 **그냥 무시**하므로,
마커는 스키마 변경도 백엔드 코드 변경도 없이 공짜로 실려 다닙니다. 텍스트 에디터도 같은 패턴입니다
(`textToHtmlBody`/`parseTextMarker` — 원본 텍스트를 `<!--optext:…-->`로 보존).

저장 시점(블록 에디터):

`frontend/src/pages/EmailEditor.tsx`
```ts
      const payload = JSON.stringify({ name: name.trim(), subject: subject.trim(), htmlBody: blocksToHtmlBody(blocks) });
      const res = id
        ? await api(`/api/templates/${id}`, { method: "PUT", body: payload })
        : await api("/api/templates", { method: "POST", body: payload });
```

다시 열 때는 마커 접두사로 **어느 에디터 소생인지** 판별해 라우팅합니다:

`frontend/src/outpace/blocks.ts`
```ts
/** Which editor should open a saved template. */
export function editorRouteFor(t: { id: number; htmlBody: string }): string {
  if (t.htmlBody.startsWith(BLOCKS_PREFIX)) return `/editor/${t.id}`;
  if (t.htmlBody.startsWith(TEXT_PREFIX)) return `/editor/text/${t.id}`;
  return `/editor/html/${t.id}`;
}
```

블록의 본문 필드는 캔버스에서 contentEditable로 직접 편집하는 **제한된 리치 HTML**인데,
그대로 두면 붙여넣기 등으로 임의 태그가 흘러들어옵니다. 그래서 화이트리스트 새니타이저가
b/strong/i/em/a/br만 남기고 전부 벗겨냅니다:

`frontend/src/outpace/blocks.ts`
```ts
const ALLOWED_TAGS = new Set(["B", "STRONG", "I", "EM", "A", "BR"]);

/**
 * Whitelist sanitizer for inline-edited HTML: keeps b/strong/i/em/a/br,
 * unwraps everything else (contentEditable's div/p line wrappers become <br>),
 * and strips all attributes except a safe http(s)/mailto href on links.
 */
export function sanitizeRich(html: string): string {
```

`{{변수}}`는 세 에디터 모두에서 **그냥 텍스트로 통과**합니다 — 치환은 어디까지나 발송 시점
worker의 일이므로(3-6절), 에디터는 변수 토큰을 넣어 주기만 하면 됩니다.

### 3-9. 이미지 업로드 — FileStorage 포트와 공개 /uploads/**

블록 에디터의 이미지/배경이미지 상자가 쓰는 업로드 경로입니다. 헥사고날 구조 그대로:
코어에 포트, infra에 로컬 디스크 어댑터, mail-api에 엔드포인트.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/FileStorage.java`
```java
public interface FileStorage {

    /**
     * Store the content under a new unique name with the given extension.
     *
     * @return the stored file name (e.g. {@code "3f2a…b1.png"}), later served
     *         under the public uploads path
     */
    String store(String extension, byte[] content);
}
```

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/UploadService.java`
```java
    /** POC guardrail: big enough for hero images, small enough to not care about disk. */
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp");
```

```java
    public String storeImage(String contentType, byte[] content) {
        String extension = EXTENSION_BY_TYPE.get(contentType == null ? "" : contentType.toLowerCase());
        if (extension == null) {
            throw new IllegalArgumentException("unsupported image type: " + contentType
                    + " (allowed: " + String.join(", ", EXTENSION_BY_TYPE.keySet()) + ")");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("file too large: " + content.length + " bytes (max " + MAX_BYTES + ")");
        }
        String storedName = storage.store(extension, content);
        return baseUrl + "/uploads/" + storedName;
    }
```

반환 URL이 `app.base-url` 기반의 **절대 URL**인 이유: 이미지는 수신자의 메일 클라이언트가
자기 쪽에서 가져가므로, 상대 경로는 앱 밖에서 죽습니다(트래킹 링크와 같은 제약).

`infra/src/main/java/io/github/ahrimjang/mail/infra/storage/LocalFileStorage.java`
```java
    @Override
    public String store(String extension, byte[] content) {
        // UUID name: no user input in the path, no collisions, no traversal surface.
        String name = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store upload " + name, e);
        }
        return name;
    }
```

`mail-api/src/main/java/io/github/ahrimjang/mail/api/UploadController.java`
```java
    /** Accept one image (multipart field "file") and return its public URL. */
    @PostMapping
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String url = uploads.storeImage(file.getContentType(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("url", url));
    }
```

저장된 파일은 같은 디렉터리(`app.uploads.dir`)를 정적으로 서빙해 공개합니다:

`mail-api/src/main/java/io/github/ahrimjang/mail/api/UploadsWebConfig.java`
```java
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(Path.of(uploadsDir).toUri().toString())
                .setCachePeriod(3600);
    }
```

권한은 비대칭입니다: **업로드(`POST /api/uploads`)는 JWT 필요**(permitAll 목록에 없음),
**조회(`/uploads/**`)는 `SecurityConfig`에서 permitAll** — 수신자 메일 클라이언트가 인증 없이
가져가야 하기 때문입니다.

프론트 쪽은 숨겨진 file input → multipart POST → 응답 URL을 블록에 꽂는 작은 컴포넌트입니다:

`frontend/src/pages/EmailEditor.tsx`
```tsx
/* Hidden-input file picker → POST /api/uploads → public URL callback. */
function UploadButton({ onUploaded, label }: { onUploaded: (url: string) => void; label?: string }) {
```

```tsx
      const fd = new FormData();
      fd.append("file", file);
      const res = await api("/api/uploads", { method: "POST", body: fd });
      if (res.ok) {
        onUploaded((await res.json()).url);
      } else {
        const data = await res.json().catch(() => ({}));
        setErr(data.error ?? "업로드에 실패했습니다.");
      }
```

이미지 상자는 `onUploaded={(url) => patch({ url })}`, 배경 이미지는
`onUploaded={(url) => patch({ bgImage: url })}`로 같은 버튼을 재사용합니다.

## 4. 설계 포인트 — 왜 이렇게

- **스냅샷 vs 발송 시점 렌더의 분리.** "어떤 내용을 보낼지"는 캠페인 생성 시 고정(스냅샷)하고,
  "누구에게 어떻게 보일지"는 발송 시점에 결정(렌더)합니다. 스냅샷 덕에 템플릿을 나중에 고쳐도
  진행 중인 캠페인이 흔들리지 않고, 발송 시점 렌더 덕에 수신자 10만 명이어도 본문을 10만 벌 미리 저장할 필요가 없습니다.
- **모르는 변수 = 빈 문자열.** 오타 하나로 대량 발송이 통째로 실패하는 것보다, 빈칸으로 나가는 쪽을 택한 MVP 트레이드오프입니다.
- **트랜잭셔널은 즉시 렌더.** 요청자가 변수를 직접 주므로 기다릴 이유가 없고, 렌더 결과가 캠페인 스냅샷에 남아 "무엇이 나갔는지"가 기록됩니다.
- **렌더러는 core의 순수 컴포넌트.** 정규식 치환뿐이라 프레임워크 의존이 없고, 나중에 Handlebars/Liquid 등으로 교체해도 호출부는 그대로입니다.
- **편집 상태는 마커로, 스키마는 그대로.** 블록 구조를 별도 컬럼/테이블에 저장하는 대신
  `htmlBody` 앞의 HTML 주석에 접어 넣었습니다. 백엔드·DB·발송 파이프라인이 에디터의 존재를
  전혀 모른 채(주석은 무시되므로) 재편집이 가능해지는 프론트 단독 해법 — 대신 마커를 손으로
  지우면 블록 에디터로는 못 열고 HTML 에디터로만 열립니다(`editorRouteFor`의 폴백).
- **업로드 URL은 절대 경로, 파일명은 UUID.** 이미지는 수신자 쪽에서 fetch되므로
  `app.base-url` 기반 절대 URL이어야 하고, 저장 파일명에 사용자 입력을 쓰지 않아
  경로 탐색(traversal)·충돌 걱정이 없습니다. 타입 화이트리스트(png/jpg/gif/webp) + 5MB 제한은
  POC 수준의 가드레일이고, S3/GCS 전환은 `FileStorage` 포트 구현 교체로 끝납니다.

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

# 5) 이미지 업로드 (JWT 필요) → 공개 URL 반환, 인증 없이 조회 가능
curl -s -X POST http://localhost:8080/api/uploads \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@logo.png"
# → {"url":"http://localhost:8080/uploads/3f2a….png"}   (curl로 그 URL을 열면 토큰 없이 200)
```

MailHog(`http://localhost:8025`)에서 실제 도착한 메일의 제목/본문에 변수가 치환됐는지,
직접 수신자(recipients) 캠페인은 contact가 없어 `{{firstName}}`이 빈칸으로 나가는지 비교해 보세요.

에디터/마커 확인: 프론트(`http://localhost:5173`)의 템플릿 페이지에서 블록 에디터로 템플릿을
만들어 저장한 뒤 `GET /api/templates/{id}`를 열면 `htmlBody`가 `<!--opblocks:…-->` 주석으로
시작하고, 템플릿 목록에서 다시 열면 상자 구조 그대로 복원됩니다. 그 템플릿으로 발송해도
MailHog에 도착한 본문에서 주석은 렌더에 아무 영향이 없습니다.
