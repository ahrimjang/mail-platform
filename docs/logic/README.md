# docs/logic — 플랫폼 로직 단계별 해설

이 코드베이스를 처음 보는 개발자를 위한 워크스루 문서 모음입니다.
각 문서는 같은 형식을 따릅니다: **개요 → 흐름(ASCII) → 단계별 실제 코드 → 설계 포인트 → 확인 방법(curl/화면)**.
코드 조각은 전부 저장소의 실제 파일에서 그대로 인용한 것이고, 각 조각 위에 파일 경로가 붙어 있습니다.

## 전체 아키텍처 3줄 요약

1. **API는 큐에 넣고 즉시 반환, worker가 비동기로 꺼내 보낸다** — 수신자가 몇 명이든 API 응답 속도가 같다(캠페인 생성 = 상태 저장(H2) + RabbitMQ enqueue).
2. **헥사고날(포트-어댑터)** — 도메인/유스케이스는 `mail-core`에만 있고, JPA·SMTP·RabbitMQ·JWT는 전부 `infra` 어댑터라서 교체(예: MailHog→SES)가 설정 한 줄이다.
3. **발송 결과는 메시지 상태(PENDING→SENT|FAILED|BOUNCED|SUPPRESSED), 참여(오픈/클릭)는 이벤트 집계** — 상태와 이벤트를 절대 섞지 않는다.

## 목차

| 문서 | 한 줄 소개 |
|---|---|
| 01 (작성 예정) | 전체 구조와 큐 파이프라인 — 캠페인 생성이 어떻게 fan-out되어 RabbitMQ를 거쳐 worker에서 발송되는가 |
| 02 (작성 예정) | JWT 인증 — 회원가입/로그인, `SecurityConfig` + `JwtAuthFilter`, 어떤 경로가 공개인가 |
| 03 (작성 예정) | 실발송과 억제 — SMTP(MailHog) 발송, 수신거부 토큰, suppression 리스트가 발송을 막는 지점 |
| 04 (작성 예정) | 오픈/클릭 추적 — 1x1 픽셀, 클릭 리다이렉트, `EmailEvent` 기반 캠페인 지표 |
| 05 (작성 예정) | 바운스 웹훅 — `POST /api/webhooks/generic` 수신 → 억제 반영 + 메시지 correlation |
| [06-templates-personalization.md](06-templates-personalization.md) | 템플릿 CRUD와 `{{변수}}` 렌더러 — 캠페인은 스냅샷 후 발송 시점 렌더, 트랜잭셔널은 즉시 렌더, 미리보기 |
| [07-contacts-lists.md](07-contacts-lists.md) | 연락처(속성 JSON)·리스트·CSV 임포트 — `listId` 캠페인이 contactId를 실어 fan-out하여 개인화로 이어지는 흐름 |

읽는 순서는 번호 순서가 곧 기능이 쌓인 순서입니다. 급하면 06 → 07만 읽어도
"템플릿 → 리스트 타깃 → 발송 시점 개인화"라는 현재 최신 흐름을 따라갈 수 있습니다.

## 함께 보면 좋은 문서

- [../send-pipeline.svg](../send-pipeline.svg) — 발송 파이프라인 전체를 한 장으로 그린 다이어그램
- [../MVP-PLAN.md](../MVP-PLAN.md) — MVP 로드맵과 범위 결정 (M1 실발송 · M2 추적 · M3 템플릿 · M4 연락처)
- [../bounce-webhook-design.md](../bounce-webhook-design.md) — 바운스 웹훅 설계 노트 (정규화 엔드포인트, correlation 전략)
- [../../CLAUDE.md](../../CLAUDE.md) — 모듈 구성, 실행 커맨드, 컨벤션 요약

## 로컬에서 직접 따라해 보기

```bash
docker compose up -d                    # rabbitmq(5672/15672) + mailhog(1025/8025)
./gradlew :mail-api:bootRun             # REST API :8080
./gradlew :mail-worker:bootRun          # 큐 소비자 (SMTP 발송)
cd frontend && npm run dev              # 화면 :5173
```

메일 확인은 MailHog `http://localhost:8025`, 큐 상태는 RabbitMQ 관리 UI `http://localhost:15672`(guest/guest).
