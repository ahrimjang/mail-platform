# AGENTS.md

이 저장소에서 코딩 에이전트가 작업할 때 따르는 안내(CLAUDE.md 는 이 파일을 임포트한다).
상세 구현 해설은 [docs/logic/](docs/logic/README.md)(한국어, 단계별)을 읽을 것 — 이 문서는 규칙·커맨드·함정만 담는다.

## 에이전트 규칙 — AI 흔적 금지 (반드시 준수)

- "Generated with Claude", "Co-Authored-By: Claude" 등 **어떤 AI 표기도 금지** — 커밋 메시지·체인지로그·PR·코드 주석 모두. 도구의 기본 커밋 트레일러 동작보다 이 규칙이 우선.
- 소스 주석은 한국어(기존 영어 주석은 그대로 둠 — 새로 쓰거나 고치는 주석부터 한국어), README·UI 문구·문서도 한국어.

## 이 프로젝트는

대용량 이메일 발송 플랫폼 "Outpace" — **실운영 중: https://outpacemail.com** (Lightsail 4GB + Cloudflare, SES 프로덕션 액세스 심사 대기). **멀티테넌트 SaaS**(가입=워크스페이스, ADMIN/OPERATOR 역할, 발송 인프라는 플랫폼 소유 — 과금은 월 발송량 기준, 포지셔닝 "구독자 수가 아니라 보낸 만큼만"). **목표: 2026-09 전 실운영 개시·매출** — 문서·커밋에 "포트폴리오/면접/데모/POC" 류 워딩 금지(서비스 관점으로 서술). 핵심: **API는 큐에 적재 후 즉시 반환, 워커가 비동기로 드레인**.

JWT 인증 + **가입 이메일 인증(V25)·구글 로그인(V26)·베타 가입 정원**(`APP_BETA_SIGNUP_CAP`), RabbitMQ 발송 큐(DLQ), Kafka 참여 이벤트 스트림(`mail.events`), SMTP 발송(개발은 MailHog, 운영은 SES 전환 예정), 예약 발송·취소, 캠페인 기간(수집 컷오프)·임시저장·불러오기, A/B 테스트(해시 분배 + 승자 자동발송), 참여도 세그먼트(팬아웃 시점 평가), 억제/수신거부(전역 + 리스트 단위 옵트아웃), 오픈/클릭 추적(링크 랭킹 + 실행 구간), 대시보드·분석 집계, 수신자 활동 타임라인, 바운스 웹훅(generic + **SES/SNS 어댑터** — 서명검증·구독 자동승인, 토글 `APP_WEBHOOK_SNS_VERIFY_SIGNATURE`), **평판 가드 4단**(주소 품질 검증 `EmailAddressValidator` → 신규 워크스페이스 워밍업 50통 상한 `SendingWarmupService` → 바운스 억제 → 바운스율 10% 자동 정지 V32 + 일회용 도메인 가입 차단), **발신 도메인 정책 + Reply-To(V33** — `APP_SENDER_DOMAIN`), 테스트 발송·발송 전 확인 모달·빌트인 템플릿 copy-on-write, **템플릿/이메일 계층 분리**(V27 — `emails`가 캠페인에 실제 쓰는 콘텐츠, 새 캠페인은 이메일을 선택), **공개 구독 API**(V30 — X-Api-Key 로 테넌트 역해석, /developers 가이드), **인앱 알림**(V31), 월 발송량 미터링, **테넌트별 발송 속도 제한**(Postgres 토큰버킷 + TTL 파킹 큐), **Grafana 메트릭**, `{{변수}}` 템플릿(블록/텍스트/HTML 에디터), 트랜잭셔널 발송, 연락처/리스트(CSV 임포트 — 거부 사유·오타 교정 후보 반환). 로드맵: [docs/ROADMAP-scale.md](docs/ROADMAP-scale.md) · 운영: [docs/OPS-MANUAL.md](docs/OPS-MANUAL.md) · 장애 이력: [docs/OPS-LOG.md](docs/OPS-LOG.md).

## 커맨드

JDK 21 필요(`JAVA_HOME` 없으면 예: `JAVA_HOME=/c/Users/user/.jdks/corretto-21.0.11` 프리픽스). `bootRun`은 저장소 루트에서 실행된다.

```bash
docker compose up -d              # Postgres(5432) + RabbitMQ(5672/UI 15672) + Kafka(9092) + MailHog(1025/UI 8025) + Prometheus(9090) + Grafana(3000)
./gradlew :mail-api:bootRun       # REST API :8080
./gradlew :mail-worker:bootRun    # 큐 소비자 + 이벤트 프로젝션 + 예약 릴리서 (HTTP는 :8082 /actuator 메트릭뿐)
./gradlew :mail-admin:bootRun     # 어드민 스켈레톤 :8081
cd frontend && npm run dev        # Vite :5175, /api -> :8080 프록시

./gradlew :mail-core:test         # 단위 테스트 (순수 JUnit+Mockito — Spring 컨텍스트 없음)
cd frontend && npx tsc -b         # 프론트 타입체크
```

상태 초기화: `docker compose down -v`. 발송 메일: http://localhost:8025 · 큐: http://localhost:15672 · DB: `psql -h localhost -U maildb maildb`(pw `maildb`).

**배포는 master 푸시가 곧 배포**([.github/workflows/deploy.yml](.github/workflows/deploy.yml) — 이미지 빌드→ghcr→서버 pull). 서버에서 직접 빌드하지 말 것(4GB 노드 메모리 경합 — OPS-LOG 1호). 문서(`**.md`, docs/ 등)만 바뀐 푸시는 배포를 건너뛴다. 프로드 스택은 [docker-compose.prod.yml](docker-compose.prod.yml), 시크릿은 서버 `~/mail-platform/.env`(커밋 금지 — `certs/` 내용물도).

## 아키텍처 (헥사고날 멀티모듈)

의존성은 전부 `mail-core`로 향한다. `mail-core`는 web/JPA/AMQP/Kafka를 모른다(spring-context만).

```
mail-common   공유 DTO/enum (API·프론트 계약)
mail-core     domain + port + service (유스케이스 전부 여기)
infra         어댑터: JPA(persistence/), RabbitMQ·Kafka(messaging/), SMTP(mail/), JWT/BCrypt(security/), 파일(storage/)
mail-api      REST 컨트롤러 :8080 (+ SecurityConfig/JwtAuthFilter, BuiltinTemplateSeeder)
mail-worker   MailSendListener·CampaignFanoutListener(@RabbitListener) · EmailEventProjectionListener(@KafkaListener) · ScheduledCampaignReleaser(10초) · AbWinnerScheduler(30초)
frontend      React18+Vite+TS, 의존성 없는 콘솔(op- 클래스, src/outpace.css) — 랜딩/요금제/가이드(/developers)+대시보드/캠페인/이메일 허브/분석/수신자/리스트/알림/관리(ADMIN) + 전체화면 에디터 3종, 720px 이하는 햄버거 네비
```

상태: 캠페인 `QUEUED → EXPANDING → SENDING → COMPLETED`(`EXPANDING`은 리스트 캠페인을 워커가 팬아웃하는 동안만 — 애드혹 `recipients[]`는 건너뜀; 릴리스 전 예약만 `CANCELED` 가능); 메시지 `PENDING → SENDING → SENT|FAILED|BOUNCED|SUPPRESSED|CANCELED`. 참여(오픈/클릭)는 이벤트 파생이지 상태가 아니다. Postgres는 상태 저장소, 큐는 RabbitMQ(발송 큐 + 팬아웃 큐). 리스트 캠페인 생성은 팬아웃 잡 1건만 발행하고 즉시 반환(O(1)).

## 반드시 알아야 할 불변식·함정

- **스키마는 Flyway 소유**(`infra/.../db/migration/V*.sql`, 현재 V33) + `ddl-auto: validate` — 엔티티 변경엔 새 `V<n>__*.sql` 필수. **enum 값 추가 시 V1의 CHECK 제약 재생성도 필요**(V4가 선례). 장문 컬럼은 `text`(`@Lob` 금지 — Postgres에서 OID 참조가 됨).
- **동시성은 전부 원자적 조건부 UPDATE claim**으로 푼다: 발송 claim(PENDING→SENDING + stale 재클레임), 팬아웃 claim(`QUEUED→EXPANDING`), 예약 릴리스/취소(상호배제), A/B 승자 판정(`ab_winner IS NULL`), 완료 판정(`completeIfSending` — boolean 반환으로 claim 승자만 알림 발행), 테넌트 발송 토큰버킷(리필+차감 UPDATE 한 문장, **토큰 확인은 claim보다 먼저**). 새 동시성 문제도 같은 패턴을 따를 것.
- **캠페인 등록 게이트 체인**(CampaignService.create): ①가입 이메일 인증 ②평판 정지(suspensionGuard) ③발신 정책(senderPolicy — `APP_SENDER_DOMAIN` 설정 시 발신 주소 제한, Reply-To 형식 검사) ④플랜 한도 ⑤워밍업(누적 200통 미만이면 캠페인당 50명). 새 발송 게이트도 이 시점에 건다 — 발송 중 컷오프 금지.
- **공개 경로는 `SecurityConfig` permitAll에 명시**: `/api/auth/**`, `/api/health`, `/api/unsubscribe/**`, `/api/track/**`, `/api/webhooks/**`, `/api/public/**`(X-Api-Key), `/api/plans`, `/uploads/**`. 나머지는 Bearer 필수, 실패는 401(403이면 프론트 재로그인이 안 뜸). nginx 가 `/api/auth/**`에 IP당 속도 제한을 별도로 건다(CF-Connecting-IP 기준).
- **수신자의 구독 결정은 별도 기록으로 보존** — 전역은 `suppressions`, 리스트 단위는 `list_unsubscribes`. 멤버십은 운영자의 분류일 뿐이므로 **해지를 멤버십 삭제로 구현하지 말 것**(CSV 재가져오기가 뒤집는다).
- **테넌트 격리 원칙(V16)**: 루트 엔티티만 `workspace_id`(자식은 부모 경유), by-id 접근은 소유 검증 후 **404**(403 금지). 공개 경로는 토큰→캠페인→워크스페이스 역해석. 콘솔 서비스는 `WorkspaceContext` 포트로 테넌트를 해석하고 **워커에서는 절대 호출 금지**(캠페인 행에서 역해석). 억제·연락처 유니크는 `(workspace_id, email)`.
- **공유 DTO는 `mail-common`에 정의하고 `frontend/src/types.ts`에 미러링** — 한쪽만 고치면 안 된다.
- **설정은 전부 `${ENV_VAR:개발기본값}`** — 로컬은 무설정 동작. 실제 `.env` 커밋 금지. 카탈로그: [.env.example](.env.example).
- **에디터 상태는 htmlBody 안의 `<!--opblocks/optext:...-->` 마커**로 영속화 — 모델·직렬화기는 `frontend/src/outpace/blocks.ts`. 빌트인 원본은 `mail-core`의 `BuiltinTemplates`. 빌트인은 삭제 불가(숨기기만), `POST /api/templates/{id}/reset`으로 복원.
- **프론트 오버레이는 `components/Portal.tsx`로** document.body에 렌더. **sandbox iframe의 srcdoc 갱신은 리페인트가 안 될 수 있다** — 내용이 바뀌면 key로 리마운트(HtmlEditor 참조).
- **운영 compose 의 회복탄력성 규칙**(OPS-LOG 2호): 사용자 대면 서비스(front)에 `depends_on: service_healthy` 금지 — `service_started`로(의존이 아픈 동안 사이트가 볼모로 잡힌다). 헬스체크는 검사 대상보다 가벼울 것(JVM 스폰형 금지 — Kafka 는 `/dev/tcp` TCP 응답). 헬스 예산은 최악 실측 기준(api 7분).

## 기능 추가 방법

- 도메인 동작 → `mail-core`(외부가 필요하면 포트 확장) · 어댑터 → `infra` · 엔드포인트 → `mail-api` 컨트롤러가 core 서비스에 위임(공개면 permitAll 추가) · DTO → `mail-common` + `types.ts` 미러링 · 엔티티 변경 → Flyway 마이그레이션.
