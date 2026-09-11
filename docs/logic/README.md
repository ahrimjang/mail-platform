# docs/logic — 플랫폼 로직 단계별 해설

이 코드베이스를 처음 보는 개발자를 위한 워크스루 문서 모음입니다.
각 문서는 같은 형식을 따릅니다: **개요 → 흐름(ASCII) → 단계별 실제 코드 → 설계 포인트 → 확인 방법(curl/화면)**.
코드 조각은 전부 저장소의 실제 파일에서 그대로 인용한 것이고, 각 조각 위에 파일 경로가 붙어 있습니다.

## 전체 아키텍처 3줄 요약

1. **API는 큐에 넣고 즉시 반환, worker가 비동기로 꺼내 보낸다** — 수신자가 몇 명이든 API 응답 속도가 같다(캠페인 생성 = 상태 저장(Postgres) + RabbitMQ enqueue, 예약 캠페인은 시각 도래 시 worker가 릴리스).
2. **헥사고날(포트-어댑터)** — 도메인/유스케이스는 `mail-core`에만 있고, JPA·SMTP·RabbitMQ·Kafka·JWT·파일스토리지는 전부 `infra` 어댑터라서 교체(예: MailHog→SES)가 설정 한 줄이다.
3. **발송 결과는 메시지 상태(PENDING→SENDING→SENT|FAILED|BOUNCED|SUPPRESSED|CANCELED), 참여(오픈/클릭/바운스)는 Kafka 이벤트 스트림(`mail.events`) → worker 프로젝션 → 집계** — 상태와 이벤트를 절대 섞지 않는다. 종료 상태 기록은 claim 토큰이 붙은 조건부 UPDATE 다(13 문서).

## 목차

| 문서 | 한 줄 소개 |
|---|---|
| [01-auth-jwt.md](01-auth-jwt.md) | JWT 인증 — 회원가입/로그인, `SecurityConfig` + `JwtAuthFilter`, 어떤 경로가 공개인가 |
| [02-campaign-queue-rabbitmq.md](02-campaign-queue-rabbitmq.md) | 캠페인 생성 → Postgres 저장 → RabbitMQ 발행 → worker 소비 — 비동기 파이프라인의 뼈대 + **예약 발송**(원자적 릴리스), 집계 발송 로그, **참여도 세그먼트**(팬아웃 시점 평가) |
| [03-dispatch-suppression.md](03-dispatch-suppression.md) | dispatchOne 한 건의 일생 — 억제 체크(토큰 앞), 원자적 클레임 + **claim 토큰 조건부 종료 기록**, HTML 조립, 발신자(From)·회신·원클릭 수신거부 헤더, SENT/BOUNCED |
| [04-tracking-analytics.md](04-tracking-analytics.md) | 오픈/클릭 추적 — 1x1 픽셀, 클릭 리다이렉트, **Kafka `mail.events` 스트림 → worker 프로젝션** → distinct 집계 기반 캠페인 지표 + **링크별 클릭 랭킹, 실행 구간(completed_at), 분석 대시보드**(퍼널/건강도/히트맵) |
| [05-bounce-webhook.md](05-bounce-webhook.md) | 바운스 웹훅 — `POST /api/webhooks/generic` 수신 → 억제 반영 + `X-Mail-Message-Id` correlation, BOUNCE 이벤트도 Kafka로 + **운영 경로 SES/SNS 어댑터**(아마존 서명검증·구독 핸드셰이크·SES 파서) |
| [06-templates-personalization.md](06-templates-personalization.md) | 템플릿 CRUD와 `{{변수}}` 렌더러 — 캠페인 스냅샷/트랜잭셔널 즉시 렌더, **에디터 3종의 마커 영속화와 이미지 업로드** |
| [07-contacts-lists.md](07-contacts-lists.md) | 연락처(속성 JSON)·리스트·CSV 임포트 — 리스트 팬아웃 개인화 + **구독 상태 관리(전역/리스트 단위)·수신자 상세·동의 기록·페이지드 테이블**(배치 병합) |
| [08-unit-tests.md](08-unit-tests.md) | 단위 테스트 가이드 — mail-core 전 테스트를 메소드별로 해설 (무엇을, 어떻게 검증하는가) |
| [09-ab-testing.md](09-ab-testing.md) | A/B 테스트 — SHA-256 결정적 분배, 홀드아웃(미발행 PENDING), **승자 자동발송**(원자적 claim + 근거 요건·유예·24h 확정)과 변형별 지표 |
| [10-multitenancy.md](10-multitenancy.md) | 멀티테넌시 — 가입=워크스페이스, ADMIN/OPERATOR 역할, **루트 엔티티 격리**(by-id 404), 공개 경로 토큰 역해석, `WorkspaceContext` 포트 (당시 BYO 과금 모델은 V20에서 발송량 과금으로 전환) |
| [11-send-throttling.md](11-send-throttling.md) | 테넌트별 발송 속도 제한 — **Postgres 토큰버킷**(원자적 조건부 UPDATE 재사용) + TTL 파킹 큐, claim 앞 토큰 확인, noisy neighbor 실측 |
| [12-metrics-grafana.md](12-metrics-grafana.md) | 메트릭 대시보드 — Micrometer 어댑터 계측 → Prometheus → **파일 프로비저닝된 Grafana**, 패널/PromQL 작성 가이드(RED·USE) |
| [13-recovery-and-abort.md](13-recovery-and-abort.md) | **"claim 에 이긴 뒤"의 설계** — claim 토큰 조건부 종료 기록, SMTP 타임아웃, DLQ 리스너, 복구 스위퍼(EXPANDING 고착·고아·stale·홀드아웃), 발송 중 중단 + 같은 시기의 한도·A/B·억제·참여도 변경 노트 |

읽는 순서는 번호 순서가 곧 기능이 쌓인 순서입니다.

> 참고: V16 멀티테넌시 전환으로 일부 포트 메소드가 워크스페이스 스코프 이름으로 바뀌었습니다
> (`findByEmail` → `findByWorkspaceAndEmail`, `findAll` → `findByWorkspace` 등). 01~09 문서의
> 오래된 인용에 남은 옛 이름은 [10-multitenancy.md](10-multitenancy.md)의 규칙으로 읽으면 됩니다.

## 함께 보면 좋은 문서

- [../send-pipeline.svg](../send-pipeline.svg) — 발송 파이프라인(즉시 발송 경로)을 한 장으로 그린 다이어그램
- [../bounce-webhook-design.md](../bounce-webhook-design.md) — 바운스 웹훅 설계 노트 (정규화 엔드포인트, correlation 전략 — SES/SNS 전환의 배경 설계)
- [../ROADMAP-scale.md](../ROADMAP-scale.md) · [../TODO-ses-sns.md](../TODO-ses-sns.md) — 앞으로 할 일 (확장성 / 실발송 전환)
- [../../AGENTS.md](../../AGENTS.md) — 모듈 구성, 실행 커맨드, 불변식·함정 요약(CLAUDE.md 는 이 파일을 임포트)

## 로컬에서 직접 따라해 보기

```bash
docker compose up -d                    # postgres + rabbitmq + kafka + mailhog + prometheus(9090) + grafana(3000)
./gradlew :mail-api:bootRun             # REST API :8080
./gradlew :mail-worker:bootRun          # 큐 소비자 (SMTP 발송)
cd frontend && npm run dev              # 화면 :5175
```

메일 확인은 MailHog `http://localhost:8025`(로컬 전용 — 운영은 SES), 큐 상태는 RabbitMQ 관리 UI `http://localhost:15672`(guest/guest).
