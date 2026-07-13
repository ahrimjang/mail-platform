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
- **구독 상태 관리** — `GET/PUT /api/contacts/{id}/subscription`. 연락처 화면에서 구독/해지를
  조회·토글하되, 저장은 여전히 억제(suppression) 테이블 하나로만 합니다(수동 처리는 reason `"manual"`).
- **리스트 라벨** — `GET/PUT /api/contacts/{id}/lists`. 연락처 쪽에서 "이 사람이 속한 리스트 집합"을
  통째로 교체(diff 방식)할 수 있습니다.
- **리스트 rename/삭제** — `PUT/DELETE /api/lists/{id}`. 삭제 시 멤버십 행도 함께 지우되 연락처는 남깁니다.

중요한 원칙 하나: **Contact에는 상태(구독중/해지 같은)가 없습니다.**
"보내도 되는가"는 오직 억제(suppression) 리스트가 발송 시점에 결정합니다.
구독 상태 API도 별도 컬럼을 만들지 않고 이 억제 테이블을 **읽어서 파생**시킨 뷰일 뿐입니다.

## 2. 흐름

```
[데이터 관리]
POST /api/contacts ──────────> ContactService ──> ContactRepository(JPA, 속성은 JSON 컬럼)
POST /api/contacts/import ───> importCsv (중복 이메일 skip, listId 있으면 멤버십 추가)
POST /api/lists ─────────────> ContactListService ──> ContactListRepository
PUT  /api/lists/{id} ────────> update (이름/설명 변경)
DELETE /api/lists/{id} ──────> delete (멤버십 동반 삭제, 연락처는 유지)
POST /api/lists/{id}/members/{contactId} ──> list_memberships 행 추가 (중복 방지)

[연락처 관점 관리]
GET/PUT /api/contacts/{id}/subscription ──> SuppressionService.subscriptionOf / updateSubscription
        (suppression 테이블에서 파생 — save(reason="manual") 또는 deleteByEmail)
GET/PUT /api/contacts/{id}/lists ─────────> ContactListService.listsOf / replaceListsOf
        (멤버십 집합을 diff 방식으로 교체)

[리스트 타깃 발송]
POST /api/campaigns {templateId, listId}
        │
        ▼
CampaignService.create
        │  countByListId로 빈 리스트만 검증 — 멤버 확장 없음, 팬아웃 잡 1건 발행 (O(1) 반환)
        ▼
RabbitMQ(mail.fanout.queue) ──> (worker) CampaignFanoutService.expand
        │  contacts.findByListIdAfter(listId, afterId, 1000)  ← keyset 페이지로 멤버 스트리밍
        │  멤버마다 MailMessage.queued(campaignId, email, contactId)   ← contactId 연결!
        ▼
RabbitMQ(mail.send.queue) ──> (worker) dispatchOne ──> contactId로 Contact 로드 ──> toVariables() ──> 개인화 발송
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

리스트 캠페인의 팬아웃은 이제 `create()`가 아니라 **worker의 `CampaignFanoutService`가 비동기로**
수행합니다(02 문서 3-6 참고 — `create()`는 팬아웃 잡 1건만 발행하고 O(1)로 반환). 멤버를 keyset
페이지로 스트리밍하며 배치마다 큐 행을 만드는 게 아래 루프입니다:

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignFanoutService.java`
```java
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
```

핵심은 그대로입니다: 멤버마다 `MailMessage.queued(campaignId, email, **contactId**)`로 큐 행을
만들어, worker가 나중에 그 연락처의 속성으로 개인화할 수 있게 합니다(06 문서 3-6 참고).
직접 recipients 방식은 contactId 없이(=개인화 변수는 email뿐) `create()` 안에서 인라인으로 유지됩니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/domain/MailMessage.java`
```java
    /** Factory for a newly enqueued, not-yet-sent message linked to a contact for personalization. */
    public static MailMessage queued(Long campaignId, String recipient, Long contactId) {
```

### 3-8. 구독 상태: 억제 테이블에서 파생되는 뷰

"이 연락처는 구독 중인가?"에 답하기 위해 Contact에 컬럼을 추가하지 않았습니다.
대신 억제 테이블을 이메일로 조회해 그때그때 **파생**합니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/SuppressionService.java`
```java
    /** Reason recorded when an operator toggles the subscription state by hand. */
    static final String MANUAL_REASON = "manual";
```

```java
    /** Subscription state of the given contact, derived from the suppression list. */
    public SubscriptionView subscriptionOf(Long contactId) {
        Contact contact = requireContact(contactId);
        return toView(suppressions.findByEmail(contact.getEmail()));
    }

    /**
     * Set the contact's subscription state: suppressed=true registers a manual
     * suppression (idempotent), false removes any existing suppression.
     * Returns the refreshed state.
     */
    public SubscriptionView updateSubscription(Long contactId, boolean suppressed) {
        Contact contact = requireContact(contactId);
        if (suppressed) {
            suppressions.save(Suppression.of(contact.getEmail(), MANUAL_REASON));
        } else {
            suppressions.deleteByEmail(contact.getEmail());
        }
        return toView(suppressions.findByEmail(contact.getEmail()));
    }
```

토글이 하는 일은 억제 행 **save 또는 delete**가 전부입니다. 운영자가 손으로 끊은 경우는
reason `"manual"`로 남아 `"unsubscribe"`(수신자 본인)·`"bounce"`(자동)와 구분됩니다.
반환 뷰는 저장 후 다시 조회한 값이라 항상 테이블의 현재 상태를 비춥니다.

`mail-api/src/main/java/io/github/ahrimjang/mail/api/ContactController.java`
```java
    /** Subscription state of this contact, derived from the suppression list. */
    @GetMapping("/{id}/subscription")
    public SubscriptionView subscription(@PathVariable Long id) {
        return suppressions.subscriptionOf(id);
    }

    /** Suppress (true) or unsuppress (false) this contact's address. */
    @PutMapping("/{id}/subscription")
    public SubscriptionView updateSubscription(@PathVariable Long id, @RequestBody UpdateSubscriptionRequest request) {
        return suppressions.updateSubscription(id, request.suppressed());
    }
```

`mail-common/src/main/java/io/github/ahrimjang/mail/common/SubscriptionView.java`
```java
public record SubscriptionView(
        boolean suppressed,
        String reason,
        Instant since
) {
}
```

### 3-9. 리스트 라벨: 멤버십 집합의 diff 교체

연락처 화면에서 소속 리스트를 체크박스로 편집하면, 프론트는 최종 집합을 통째로 보냅니다
(`PUT /api/contacts/{id}/lists`, body `{"listIds":[...]}`). 서비스는 중복 제거와 존재 검증을
먼저 끝내고 교체를 위임합니다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/ContactListService.java`
```java
    /** Ids of every list the contact belongs to. */
    public List<Long> listsOf(Long contactId) {
        requireContact(contactId);
        return lists.findListIdsByContactId(contactId);
    }

    /** Replace the contact's memberships with exactly the given set of lists. */
    public List<Long> replaceListsOf(Long contactId, List<Long> listIds) {
        requireContact(contactId);
        if (listIds == null) {
            throw new IllegalArgumentException("listIds is required");
        }
        List<Long> distinct = listIds.stream().distinct().toList();
        for (Long listId : distinct) {
            lists.findById(listId)
                    .orElseThrow(() -> new NoSuchElementException("list not found: " + listId));
        }
        lists.replaceMembershipsForContact(contactId, distinct);
        return lists.findListIdsByContactId(contactId);
    }
```

어댑터의 교체 구현은 "전부 지우고 다시 넣기"가 아니라 **diff**입니다 — 빠진 것만 지우고, 새로 생긴 것만 넣습니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/JpaContactListRepository.java`
```java
    @Override
    @Transactional
    public void replaceMembershipsForContact(Long contactId, List<Long> listIds) {
        // Diff-based replace: only delete what left and insert what's new.
        // A naive wipe-and-reinsert breaks here — Hibernate flushes INSERTs before
        // the deferred derived DELETEs, so re-inserting a kept membership collides
        // with its not-yet-deleted row on uk_list_memberships.
        java.util.Set<Long> target = new java.util.HashSet<>(listIds);
        java.util.Set<Long> current = new java.util.HashSet<>(findListIdsByContactId(contactId));
        for (Long listId : current) {
            if (!target.contains(listId)) {
                memberships.deleteByListIdAndContactId(listId, contactId);
            }
        }
        for (Long listId : target) {
            if (!current.contains(listId)) {
                memberships.save(new ListMembershipEntity(null, listId, contactId));
            }
        }
    }
```

### 3-10. 리스트 rename/삭제

`mail-api/src/main/java/io/github/ahrimjang/mail/api/ContactListController.java`
```java
    /** Rename a list / update its description. */
    @PutMapping("/{id}")
    public ContactListView update(@PathVariable Long id, @RequestBody ContactListRequest request) {
        return lists.update(id, request);
    }

    /** Delete a list (memberships go with it; contacts are kept). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        lists.delete(id);
        return ResponseEntity.noContent().build();
    }
```

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/ContactListService.java`
```java
    /** Update a list's name/description. */
    public ContactListView update(Long id, ContactListRequest request) {
        ContactList list = lists.findById(id)
                .orElseThrow(() -> new NoSuchElementException("list not found: " + id));
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        list.setName(request.name());
        list.setDescription(request.description());
        return toView(lists.save(list));
    }
```

삭제는 어댑터에서 **멤버십 먼저, 리스트 다음** 순서로 한 트랜잭션 안에서 지웁니다.
지워지는 건 관계(조인 행)뿐이고 연락처 자체는 그대로 남습니다.

`infra/src/main/java/io/github/ahrimjang/mail/infra/persistence/JpaContactListRepository.java`
```java
    @Override
    @Transactional
    public void deleteById(Long id) {
        memberships.deleteByListId(id);
        jpa.deleteById(id);
    }
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
- **구독 상태도 억제 테이블 하나로.** "구독 해지" API를 추가하면서도 진실원(source of truth)은
  늘리지 않았습니다. `subscriptionOf`는 읽기 전용 파생 뷰, `updateSubscription`은 억제 행의
  save/delete일 뿐이라 발송 파이프라인(`dispatchOne`의 `isSuppressed` 체크)이 자동으로 같은 답을 봅니다.
  reason(`"manual"`/`"unsubscribe"`/`"bounce"`)으로 경위만 구분합니다.
- **멤버십 교체는 wipe-and-reinsert가 아니라 diff.** 순진하게 "전부 delete 후 전부 insert"를 하면
  Hibernate가 파생 쿼리 DELETE의 플러시를 **INSERT보다 뒤로** 미루는 바람에, 유지되는 멤버십을
  다시 넣는 INSERT가 아직 안 지워진 기존 행과 `uk_list_memberships` 유니크 제약에서 충돌합니다
  (`JpaContactListRepository.replaceMembershipsForContact`의 주석이 이 사연입니다). diff 방식은
  이 문제를 피하는 동시에 안 바뀐 행을 건드리지 않아 쓰기량도 최소입니다.
- **리스트 삭제는 관계만 지운다.** 리스트는 이름표이므로 삭제해도 연락처 데이터는 무사합니다.
  멤버십 → 리스트 순서의 한 트랜잭션이라 고아 조인 행도 남지 않습니다.

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

# 3-1) 리스트 이름 변경 / 삭제
curl -s -X PUT http://localhost:8080/api/lists/1 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"vip-renamed","description":"이름 변경 테스트"}'
# curl -s -X DELETE http://localhost:8080/api/lists/1 -H "Authorization: Bearer $TOKEN"
#   (삭제하면 멤버십만 사라지고 GET /api/contacts 의 연락처는 그대로)

# 3-2) 연락처 1번의 구독 상태 조회 → 수동 해지 → 재구독
curl -s http://localhost:8080/api/contacts/1/subscription -H "Authorization: Bearer $TOKEN"
# → {"suppressed":false,"reason":null,"since":null}
curl -s -X PUT http://localhost:8080/api/contacts/1/subscription \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"suppressed":true}'
# → {"suppressed":true,"reason":"manual","since":"..."}  (이 상태로 캠페인을 보내면 SUPPRESSED)
curl -s -X PUT http://localhost:8080/api/contacts/1/subscription \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"suppressed":false}'

# 3-3) 연락처 1번의 소속 리스트 조회/교체 (diff 방식 — 최종 집합을 통째로 보냄)
curl -s http://localhost:8080/api/contacts/1/lists -H "Authorization: Bearer $TOKEN"
curl -s -X PUT http://localhost:8080/api/contacts/1/lists \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"listIds":[1]}'
# → [1]   (빈 배열 []을 보내면 모든 리스트에서 빠짐, null이면 400)

# 4) 리스트 타깃 + 템플릿 캠페인 (06 문서의 템플릿 1번 재사용)
curl -s -X POST http://localhost:8080/api/campaigns \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"templateId":1,"listId":1}'
```

MailHog(`http://localhost:8025`)에서 두 통이 도착하고, 각각 "민수님", "영희님"으로
**서로 다르게 렌더**됐으면 contactId 개인화까지 전 구간이 동작한 것입니다.
빈 리스트로 캠페인을 만들면 400(`list has no members`)이 떨어지는 것도 확인해 보세요.
