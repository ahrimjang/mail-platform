# 10. 멀티테넌시 — 워크스페이스, 역할, 테넌트 격리

## 1. 개요 — 무엇을, 왜

가입이 곧 **회사(테넌트) 등록**이다: 워크스페이스가 만들어지고 첫 계정이 ADMIN이 된다.
이후 모든 데이터(캠페인·수신자·리스트·템플릿·억제 목록)는 정확히 하나의 워크스페이스에
속하고, 다른 테넌트에게는 **존재하지 않는 것처럼** 보인다.

전환의 동기는 기술이 아니라 과금 모델이다. 비용이 크고 변동성 높은 인프라(발송 SMTP/SES,
파일 저장소)를 **고객 소유 계정에 연결(BYO)** 하면 그 비용이 고객에게 직접 청구되므로,
플랫폼은 사용량 미터링·정산 없이 구독료만 받으면 된다. 관리 페이지의 SMTP/저장소
프로바이더 선택이 그 자리다(현재는 선택 저장까지 — 자격증명 연동은 로드맵).

역할은 두 단계다: **ADMIN**은 워크스페이스(설정·멤버)를 운영하고, **OPERATOR**는
캠페인을 운영한다. 관리 API의 변경 연산은 전부 ADMIN 전용이다.

## 2. 격리 모델 — 어디에 workspace_id를 두나

```
workspaces ──┬── users (role: ADMIN | OPERATOR)
             ├── campaigns ──── mail_messages ──── email_events     ← 자식은 부모 경유
             ├── contacts ───── list_memberships / list_unsubscribes
             ├── contact_lists
             ├── templates (workspace_id null = 전역 빌트인)
             └── suppressions (unique (workspace_id, email))
```

- **루트 엔티티 5개에만** `workspace_id`(V16). 메시지·이벤트·멤버십·옵트아웃 같은
  자식 테이블은 부모를 거쳐 격리되므로 컬럼이 없다 — 조인 한 번이면 충분하고,
  컬럼 중복은 정합성 버그의 온상이다.
- **이메일 유니크가 테넌트 단위로**: `suppressions`·`contacts`의 유니크가
  `(workspace_id, email)`이다. A사에서 수신거부한 주소가 B사 발송까지 막으면 안 되니까 —
  do-not-send 결정 자체가 테넌트의 것이다.
- **by-id 접근은 소유 검증 후 404**: 남의 캠페인 id를 넣으면 403이 아니라 404다.
  존재 여부 자체가 정보라서다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignService.java`
```java
    /** A campaign of another tenant reads as absent, never as forbidden. */
    private boolean owned(Campaign campaign) {
        return campaign.getWorkspaceId() != null
                && campaign.getWorkspaceId().equals(ctx.currentWorkspaceId());
    }
```

## 3. WorkspaceContext — 시그니처 폭발을 피한 방법

"현재 요청이 어느 테넌트인가"를 모든 서비스 메소드의 파라미터로 실어 나르면
수십 개 시그니처와 그 테스트가 전부 바뀐다. 대신 **포트 하나**로 뽑았다:

`mail-core/src/main/java/io/github/ahrimjang/mail/core/port/WorkspaceContext.java`
```java
public interface WorkspaceContext {
    Long currentWorkspaceId();
    boolean isAdmin();
    String currentUserEmail();
}
```

- **API 어댑터**(`SecurityWorkspaceContext`)가 요청의 SecurityContext(JWT 필터가 채움)에서
  이메일을 꺼내 **DB에서 사용자를 조회**해 답한다. JWT에는 이메일(subject)만 있다 —
  역할·워크스페이스를 토큰에 넣지 않아서, 역할이 바뀌면 **토큰 재발급 없이** 다음
  요청부터 반영된다(대신 요청당 사용자 조회 1회 — 유니크 인덱스라 저렴).
- **워커에는 요청이 없다.** 백그라운드 경로(발송·팬아웃·프로젝션)는 컨텍스트 대신
  **캠페인 행에서 테넌트를 역해석**한다. 워커의 `WorkspaceContext` 빈은 호출되면
  예외를 던지는 가드다 — 콘솔용 서비스를 워커 컨텍스트에 와이어링하기 위해서만 존재한다.

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
        if (suppressions.existsByWorkspaceAndEmail(campaign.getWorkspaceId(), message.getRecipient())) {
```

## 4. 비인증 공개 경로 — 토큰이 테넌트를 알려준다

추적 픽셀/클릭, 수신거부, 바운스 웹훅은 로그인이 없다. 전부 **메시지 단위 토큰**으로
진입하므로 토큰→메시지→캠페인→워크스페이스로 역해석한다:

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/SuppressionService.java`
```java
    public void suppressByUnsubToken(String token) {
        messages.findByUnsubToken(token).ifPresent(m ->
                campaigns.findById(m.getCampaignId()).ifPresent(c ->
                        suppressions.save(Suppression.of(c.getWorkspaceId(), m.getRecipient(), "unsubscribe"))));
    }
```

바운스 웹훅에서 하나 중요한 결정: 메시지 correlation(`X-Mail-Message-Id`)이 **없으면
억제를 저장하지 않는다.** 어느 테넌트의 결정인지 알 수 없는 억제를 저장하는 순간
모든(혹은 엉뚱한) 테넌트의 발송을 오염시키기 때문이다 — 버리는 쪽이 옳다.

## 5. 역할 가드 — ADMIN만 워크스페이스를 만진다

관리 유스케이스(`WorkspaceService`)의 변경 연산은 `ctx.isAdmin()`을 검사해
`ForbiddenException`(→403)을 던진다. 워크스페이스가 관리 불능이 되는 것을 막는
불변식 하나: **마지막 ADMIN은 강등할 수 없다**(409).

```java
        if ("OPERATOR".equals(role) && "ADMIN".equals(user.getRole()) && countAdmins() <= 1) {
            throw new IllegalStateException("cannot demote the last admin");
        }
```

프론트의 `api()` 래퍼도 이때 한 줄 고쳤다: 역할이 없던 시절 403을 "만료 세션"으로
간주하고 로그아웃시켰는데, 이제 403은 OPERATOR가 정상적으로 받는 응답이다 —
401만 재로그인 처리한다.

## 6. 알려진 한계 (의도된 순서)

- **빌트인 템플릿은 전역 공유 + 편집 가능** — 테넌트 간 오염 가능.
  copy-on-write(빌트인 읽기 전용 + "복사해서 내 템플릿으로")가 다음 수순.
- 업로드 파일 미격리, 테넌트별 rate limit·쿼터·커넥터 자격증명 미구현.
- 초대 메일·비밀번호 재설정 등 계정 수명주기 없음.
- 전체 목록: [REVIEW-product.md](../REVIEW-product.md).

## 7. 확인 방법

```bash
# 회사 A 가입 → 워크스페이스 + ADMIN
curl -s -X POST localhost:8080/api/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"admin@a.com","password":"pass1234","displayName":"에이","companyName":"A컴퍼니"}'
# → {"token":"...","workspaceName":"A컴퍼니","role":"ADMIN"}

# 격리: A의 토큰으로는 다른 테넌트 데이터가 안 보인다 (목록 빈 배열, by-id 404)
curl -s localhost:8080/api/campaigns -H "Authorization: Bearer $A_TOKEN"          # []
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/campaigns/1 -H "Authorization: Bearer $A_TOKEN"  # 404

# 운영자 생성 후 관리 API 호출 → 403
curl -s -X POST localhost:8080/api/workspace/users -H "Authorization: Bearer $A_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"op@a.com","password":"pass1234","role":"OPERATOR"}'
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/workspace/users -H "Authorization: Bearer $OP_TOKEN"  # 403
```
