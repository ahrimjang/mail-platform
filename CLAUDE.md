# CLAUDE.md

이 저장소에서 Claude Code가 작업할 때 따르는 안내. 상세 구현 해설은 [docs/logic/](docs/logic/README.md)(한국어, 단계별)을 읽을 것 — 이 문서는 규칙·커맨드·함정만 담는다.

## 에이전트 규칙 — AI 흔적 금지 (반드시 준수)

- "Generated with Claude", "Co-Authored-By: Claude" 등 **어떤 AI 표기도 금지** — 커밋 메시지·체인지로그·PR·코드 주석 모두. 기본 커밋 트레일러 동작보다 이 규칙이 우선.
- 소스 주석은 영어, README·UI 문구·문서는 한국어.

## 이 프로젝트는

대용량 이메일 발송 플랫폼 POC(Notifuse 축소판), **멀티테넌트 SaaS**(가입=워크스페이스, ADMIN/OPERATOR 역할, 관리 페이지의 BYO SMTP/저장소 선택). 핵심: **API는 큐에 적재 후 즉시 반환, 워커가 비동기로 드레인**. JWT 인증, RabbitMQ 발송 큐(DLQ), Kafka 참여 이벤트 스트림(`mail.events`), SMTP 발송(개발은 MailHog), 예약 발송·취소, 캠페인 기간(수집 컷오프)·임시저장·불러오기, A/B 테스트(해시 분배 + 승자 자동발송), 참여도 세그먼트(리스트 캠페인을 오픈/클릭률로 좁혀 발송 — 팬아웃 시점 평가), 억제/수신거부(전역 + 리스트 단위 옵트아웃), 오픈/클릭 추적(캠페인별 링크 랭킹 + 실행 구간), 대시보드·분석 집계(퍼널/건강도/오픈 히트맵), 수신자 활동 타임라인, 바운스 웹훅, `{{변수}}` 템플릿(블록/텍스트/HTML 에디터 + DB 시딩 기본 템플릿), 트랜잭셔널 발송, 연락처/리스트(CSV + 동의 기록, 페이지드 테이블). 로드맵: [docs/ROADMAP-scale.md](docs/ROADMAP-scale.md) · [docs/TODO-ses-sns.md](docs/TODO-ses-sns.md).

## 커맨드

JDK 21 필요(`JAVA_HOME` 없으면 예: `JAVA_HOME=/c/Users/user/.jdks/corretto-21.0.11` 프리픽스). `bootRun`은 저장소 루트에서 실행된다.

```bash
docker compose up -d              # Postgres(5432) + RabbitMQ(5672/UI 15672) + Kafka(9092) + MailHog(1025/UI 8025)
./gradlew :mail-api:bootRun       # REST API :8080
./gradlew :mail-worker:bootRun    # 큐 소비자 + 이벤트 프로젝션 + 예약 릴리서 (웹 없음)
./gradlew :mail-admin:bootRun     # 어드민 스켈레톤 :8081
cd frontend && npm run dev        # Vite :5173, /api -> :8080 프록시

./gradlew :mail-core:test         # 단위 테스트 (22클래스/184개, 순수 JUnit+Mockito — Spring 컨텍스트 없음)
cd frontend && npx tsc -b         # 프론트 타입체크
```

상태 초기화: `docker compose down -v`. 발송 메일: http://localhost:8025 · 큐: http://localhost:15672 · DB: `psql -h localhost -U maildb maildb`(pw `maildb`). 로그 관측 스택은 별도 프로젝트(`../opensearch-log-analysis-backend`) — 이 저장소는 `./logs/*.json.log`를 생산만 한다.

## 아키텍처 (헥사고날 멀티모듈)

의존성은 전부 `mail-core`로 향한다. `mail-core`는 web/JPA/AMQP/Kafka를 모른다(spring-context만).

```
mail-common   공유 DTO/enum (API·프론트 계약)
mail-core     domain + port + service (유스케이스 전부 여기)
infra         어댑터: JPA(persistence/), RabbitMQ·Kafka(messaging/), SMTP(mail/), JWT/BCrypt(security/), 파일(storage/)
mail-api      REST 컨트롤러 :8080 (+ SecurityConfig/JwtAuthFilter, BuiltinTemplateSeeder)
mail-worker   MailSendListener·CampaignFanoutListener(@RabbitListener) · EmailEventProjectionListener(@KafkaListener) · ScheduledCampaignReleaser(10초) · AbWinnerScheduler(30초)
frontend      React18+Vite+TS, 의존성 없는 콘솔(op- 클래스, src/outpace.css) — 대시보드/캠페인/분석/템플릿/수신자/리스트/관리(ADMIN) + 전체화면 에디터 3종
```

상태: 캠페인 `QUEUED → EXPANDING → SENDING → COMPLETED`(`EXPANDING`은 리스트 캠페인을 워커가 팬아웃하는 동안만 — 애드혹 `recipients[]`는 건너뜀; 릴리스 전 예약만 `CANCELED` 가능); 메시지 `PENDING → SENDING → SENT|FAILED|BOUNCED|SUPPRESSED|CANCELED`. 참여(오픈/클릭)는 이벤트 파생이지 상태가 아니다. Postgres는 상태 저장소, 큐는 RabbitMQ(발송 큐 + 팬아웃 큐). 리스트 캠페인 생성은 팬아웃 잡 1건만 발행하고 즉시 반환(O(1)) — 실제 수신자 확장은 워커가 배치로.

## 반드시 알아야 할 불변식·함정

- **스키마는 Flyway 소유**(`infra/.../db/migration/V*.sql`) + `ddl-auto: validate` — 엔티티 변경엔 새 `V<n>__*.sql` 필수. **enum 값 추가 시 V1의 CHECK 제약 재생성도 필요**(V4가 선례). 장문 컬럼은 `text`(`@Lob` 금지 — Postgres에서 OID 참조가 됨).
- **동시성은 전부 원자적 조건부 UPDATE claim**으로 푼다: 발송 claim(PENDING→SENDING + stale 재클레임), 팬아웃 claim(`QUEUED→EXPANDING` — 중복 팬아웃 잡 멱등), 예약 릴리스(`enqueued_at IS NULL AND status='QUEUED'`), 예약 취소(같은 조건 — 릴리스와 상호배제). A/B 승자 판정(`ab_winner IS NULL` — 스케줄러 다중 기동에도 1회 결정), 완료 판정도 `SENDING`에서만(`completeIfSending` — `completed_at` 스탬프를 같은 UPDATE에 실음) — 팬아웃 중 조기완료 방지. 새 동시성 문제도 같은 패턴을 따를 것.
- **공개 경로는 `SecurityConfig` permitAll에 명시**: `/api/auth/**`, `/api/health`, `/api/unsubscribe/**`, `/api/track/**`, `/api/webhooks/**`, `/uploads/**`. 나머지는 Bearer 필수, 실패는 401(403이면 프론트 재로그인이 안 뜸).
- **수신자의 구독 결정은 별도 기록으로 보존** — 전역은 `suppressions`, 리스트 단위는 `list_unsubscribes`(팬아웃이 발송 시점 제외). 멤버십(`list_memberships`)은 운영자의 분류일 뿐 구독 의사가 아니므로 **해지를 멤버십 삭제로 구현하지 말 것**(CSV 재가져오기가 뒤집는다).
- **테넌트 격리 원칙(V16)**: 루트 엔티티 5개만 `workspace_id`(자식은 부모 경유), by-id 접근은 소유 검증 후 **404**(403 금지 — 존재를 숨김), 공개 경로(추적/수신거부/웹훅)는 토큰→캠페인→워크스페이스 역해석, correlation 없는 바운스 억제는 버림. 콘솔 서비스는 `WorkspaceContext` 포트로 테넌트를 해석하고 **워커에서는 절대 호출 금지**(캠페인 행에서 역해석). 억제·연락처 유니크는 `(workspace_id, email)`.
- **공유 DTO는 `mail-common`에 정의하고 `frontend/src/types.ts`에 미러링** — 한쪽만 고치면 안 된다.
- **설정은 전부 `${ENV_VAR:개발기본값}`** — 로컬은 무설정 동작. 실제 `.env` 커밋 금지. 카탈로그: [.env.example](.env.example).
- **에디터 상태는 htmlBody 안의 `<!--opblocks/optext:...-->` 마커**로 영속화(발송은 주석 무시, 템플릿 카드는 마커로 에디터 라우팅) — 모델·직렬화기는 `frontend/src/outpace/blocks.ts`. 기본 템플릿 원본은 `mail-core`의 `BuiltinTemplates`(블록 문서 — 디자인 변경 시 프론트 직렬화기로 재생성). 빌트인은 삭제 불가, `POST /api/templates/{id}/reset`으로 복원.
- **프론트 오버레이는 `components/Portal.tsx`로** document.body에 렌더(transform 컨테이너가 fixed를 가둠). **sandbox iframe의 srcdoc을 갱신하면 리페인트가 안 될 수 있다** — 내용이 바뀌면 key로 리마운트(HtmlEditor 참조).

## 기능 추가 방법

- 도메인 동작 → `mail-core`(외부가 필요하면 포트 확장) · 어댑터 → `infra` · 엔드포인트 → `mail-api` 컨트롤러가 core 서비스에 위임(공개면 permitAll 추가) · DTO → `mail-common` + `types.ts` 미러링 · 엔티티 변경 → Flyway 마이그레이션.
