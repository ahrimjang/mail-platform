# 07. 연락처와 리스트 (M4)

## 1. 개요 — 무엇을, 왜

지금까지 캠페인은 요청 본문에 이메일 주소를 직접 나열해야 했습니다(`"recipients": [...]`).
이 단계에서 수신자를 **데이터로 관리**하게 됩니다.

- **Contact** — 이메일 + 이름 + 자유 형식 속성(`Map<String,String>`, DB에는 JSON 문자열로 저장).
  이 속성 map이 그대로 템플릿 `{{변수}}`의 값이 됩니다(06 문서와 연결되는 지점).
- **ContactList** — 연락처를 묶는 이름 있는 그룹. 멤버십은 별도 조인 테이블(`list_memberships`)로 관리.
- **CSV 임포트** — `email,firstName,lastName` 텍스트를 한 번에 부어 넣기.
- **리스트 타깃 캠페인** — `POST /api/campaigns`에 `listId`를 주면 리스트 멤버 전원에게 fan-out,
  이때 각 메시지에 `contactId`가 실려 발송 시점 개인화가 가능해집니다.

중요한 원칙 하나: **Contact에는 상태(구독중/해지 같은)가 없습니다.**
"보내도 되는가"는 오직 억제(suppression) 리스트가 발송 시점에 결정합니다.

## 2. 흐름

```
[데이터 관리]
POST /api/contacts ──────────> ContactService ──> ContactRepository(JPA, 속성은 JSON 컬럼)
POST /api/contacts/import ───> importCsv (중복 이메일 skip, listId 있으면 멤버십 추가)
POST /api/lists ─────────────> ContactListService ──> ContactListRepository
POST /api/lists/{id}/members/{contactId} ──> list_memberships 행 추가 (중복 방지)

[리스트 타깃 발송]
POST /api/campaigns {templateId, listId}
        │
        ▼
CampaignService.create
        │  contacts.findByListId(listId)  ← 조인 쿼리로 멤버 조회
        │  멤버마다 MailMessage.queued(campaignId, email, contactId)   ← contactId 연결!
        ▼
RabbitMQ ──> (worker) dispatchOne ──> contactId로 Contact 로드 ──> toVariables() ──> 개인화 발송
```

## 3. 단계별 코드

### 3-1. 도메인: Contact — 속성 map과 toVariables

`mail-core/src/main/java/io/github/ahrimjang/mail/core/domain/Contact.java`
```java
/**
 * Domain model of an addressable contact. Deliberately has no status field —
 * the suppression list remains the single do-not-send source of truth.
 * Pure POJO — no JPA / framework concerns.
 */
public class Contact {

    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private Map<String, String> attributes;
    private Instant createdAt;
```

```java
    /**
     * Flatten this contact into the variable map used for template rendering:
     * custom attributes plus the well-known {@code email}/{@code firstName}/{@code lastName} keys.
     */
    public Map<String, String> toVariables() {
        Map<String, String> vars = new HashMap<>(attributes == null ? Map.of() : attributes);
        vars.put("email", email);
        if (firstName != null) {
            vars.put("firstName", firstName);
        }
        if (lastName != null) {
            vars.put("lastName", lastName);
        }
        return vars;
    }
```

`toVariables()`가 연락처를 템플릿 변수 map으로 "펼치는" 어댑터입니다. 커스텀 속성
(`{"company":"ACME"}` 등)에 표준 키 3개(email/firstName/lastName)를 얹습니다.

### 3-2. 도메인: ContactList

`mail-core/src/main/java/io/github/ahrimjang/mail/core/domain/ContactList.java`
```java
/**
 * Domain model of a named group of contacts that campaigns can target.
 * Membership itself lives behind the repository port. Pure POJO — no JPA concerns.
 */
public class ContactList {

    private Long id;
    private String name;
    private String description;
    private Instant createdAt;
```

리스트 자체는 이름표일 뿐이고, "누가 들어 있는가"는 도메인 객체가 아니라 리포지토리 포트 뒤의 조인 테이블이 답합니다.

### 3-3. CSV 임포트

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/ContactService.java`
```java
    public ImportResult importCsv(String csv, Long listId) {
        int imported = 0;
        int skipped = 0;
        for (String line : csv.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", 3);
            String email = parts[0].trim();
            if (!email.contains("@")) {
                skipped++;
                continue;
            }
            Optional<Contact> existing = contacts.findByEmail(email);
            Contact contact = existing.orElseGet(() -> contacts.save(Contact.of(
                    email,
                    parts.length > 1 ? parts[1].trim() : null,
                    parts.length > 2 ? parts[2].trim() : null,
                    new HashMap<>())));
            if (existing.isPresent()) {
                skipped++;
            } else {
                imported++;
            }
            if (listId != null) {
                lists.addMember(listId, contact.getId());
            }
        }
        return new ImportResult(imported, skipped);
    }
```

한 줄 = `email,firstName,lastName`. 이메일이 아니면 skip, **이미 있는 이메일도 skip(멱등)** —
단 `listId`가 주어지면 기존 연락처라도 그 리스트에는 넣어 줍니다. 같은 CSV를 두 번 부어도 중복 연락처가 생기지 않습니다.

### 3-4. 저장: 속성 map → JSON 문자열 컬럼

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/JpaContactRepository.java`
```java
    private String writeAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(attributes);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize contact attributes", ex);
        }
    }
```

자유 형식 속성을 위해 컬럼을 계속 늘리는 대신, Jackson으로 직렬화한 **JSON 문자열 한 컬럼**에
담습니다. 도메인(`Map`)과 DB(문자열) 사이 변환은 전부 이 어댑터 안에서 끝나고, core는 JSON의 존재조차 모릅니다.

### 3-5. 멤버십: 조인 테이블 + 유니크 제약

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/ListMembershipEntity.java`
```java
@Entity
@Table(
        name = "list_memberships",
        uniqueConstraints = @UniqueConstraint(columnNames = {"listId", "contactId"})
)
public class ListMembershipEntity {
```

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/JpaContactListRepository.java`
```java
    @Override
    public void addMember(Long listId, Long contactId) {
        if (!memberships.existsByListIdAndContactId(listId, contactId)) {
            memberships.save(new ListMembershipEntity(null, listId, contactId));
        }
    }
```

(listId, contactId) 쌍이 유니크라 같은 사람이 같은 리스트에 두 번 들어갈 수 없습니다 —
애플리케이션에서 한 번 확인하고, DB 제약이 최후 방어선입니다.
리스트 멤버 조회는 이 조인 테이블을 타는 JPQL 하나입니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/ContactJpaRepository.java`
```java
    @Query("select c from ContactEntity c, ListMembershipEntity m where m.listId = ?1 and m.contactId = c.id order by c.id")
    List<ContactEntity> findByListId(Long listId);
```

### 3-6. API: ContactController / ContactListController

`mail-api/src/main/java/io/github/ahrimjang/mail/api/ContactController.java`
```java
    /** Import "email,firstName,lastName" lines; existing addresses are skipped, all end up in {@code listId} if given. */
    @PostMapping(value = "/import", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ImportResult importCsv(@RequestBody String csv,
                                  @RequestParam(value = "listId", required = false) Long listId) {
        return contacts.importCsv(csv, listId);
    }
```

임포트는 JSON이 아니라 `text/plain` 본문(CSV 원문)을 받습니다.

`mail-api/src/main/java/io/github/ahrimjang/mail/api/ContactListController.java`
```java
    @PostMapping("/{id}/members/{contactId}")
    public ResponseEntity<Void> addMember(@PathVariable Long id, @PathVariable Long contactId) {
        lists.addMember(id, contactId);
        return ResponseEntity.noContent().build();
    }
```

### 3-7. 리스트 타깃 캠페인: contactId를 실은 fan-out

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignService.java`
```java
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
```

`listId` 분기가 핵심입니다. 리스트 멤버마다 `MailMessage.queued(campaignId, email, **contactId**)`로
큐 행을 만들어, worker가 나중에 그 연락처의 속성으로 개인화할 수 있게 합니다(06 문서 3-6 참고).
직접 recipients 방식은 contactId 없이(=개인화 변수는 email뿐) 그대로 유지됩니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/domain/MailMessage.java`
```java
    /** Factory for a newly enqueued, not-yet-sent message linked to a contact for personalization. */
    public static MailMessage queued(Long campaignId, String recipient, Long contactId) {
```

## 4. 설계 포인트 — 왜 이렇게

- **Contact에 상태가 없다.** "구독 해지"를 연락처 상태로도, 억제 리스트로도 이중 관리하면 반드시 어긋납니다.
  do-not-send의 진실은 억제 리스트 하나이고, 발송 시점(`dispatchOne`)에만 조회합니다.
- **속성은 JSON 컬럼.** 고객마다 원하는 속성이 다른데(회사, 등급, 지역…) 스키마 변경 없이 받으려면
  자유 형식이 필요합니다. 검색·인덱싱이 안 되는 대가는 MVP에선 수용, 운영은 Postgres `jsonb`로 자연 승격.
- **멤버십은 JPA `@ManyToMany`가 아닌 명시적 조인 엔티티.** 관계 자체가 테이블로 보이니
  추가/삭제/카운트가 단순 쿼리이고, 나중에 "가입일" 같은 컬럼을 관계에 붙이기도 쉽습니다.
- **fan-out은 이메일 스냅샷 + contactId 링크.** 큐 행에 이메일을 복사해 두므로 발송 자체는 연락처가
  지워져도 진행되고(`orElse(vars)` 폴백), contactId는 개인화라는 부가 기능만 담당합니다.
- **CSV 임포트가 멱등.** 대량 데이터 작업은 "다시 실행해도 안전"이 기본값이어야 합니다.

## 5. 확인 방법

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@test.com","password":"secret1"}' | jq -r .token)

# 1) 리스트 생성
curl -s -X POST http://localhost:8080/api/lists \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"vip","description":"VIP 고객"}'

# 2) CSV 임포트 (listId=1에 바로 소속)
curl -s -X POST 'http://localhost:8080/api/contacts/import?listId=1' \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/plain' \
  --data-binary $'kim@test.com,민수,김\nlee@test.com,영희,이\nkim@test.com,중복,행'
# → {"imported":2,"skipped":1}   (같은 이메일 재등장은 skip)

# 3) 멤버 확인
curl -s http://localhost:8080/api/lists/1/members -H "Authorization: Bearer $TOKEN"

# 4) 리스트 타깃 + 템플릿 캠페인 (06 문서의 템플릿 1번 재사용)
curl -s -X POST http://localhost:8080/api/campaigns \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"templateId":1,"listId":1}'
```

MailHog(`http://localhost:8025`)에서 두 통이 도착하고, 각각 "민수님", "영희님"으로
**서로 다르게 렌더**됐으면 contactId 개인화까지 전 구간이 동작한 것입니다.
빈 리스트로 캠페인을 만들면 400(`list has no members`)이 떨어지는 것도 확인해 보세요.
