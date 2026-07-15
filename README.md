# mail-platform (POC)

대용량 이메일 발송 플랫폼의 MVP/POC. 핵심은 **API가 즉시 큐에 적재하고 반환하면, 별도 워커가 큐를 비동기로 드레인**하는 구조입니다 — 수신자 수와 무관하게 API 응답이 일정합니다.

## 기능

- **캠페인 발송** — 직접 작성 / 템플릿 스냅샷 / 연락처 리스트 팬아웃(워커가 비동기 확장 — 생성은 수신자 수와 무관하게 O(1)). 발신자(From) 지정, **예약 발송·취소**(지정 시각에 워커가 큐로 릴리스)
- **A/B 테스트** — 이메일 해시 기반 결정적 분배, 전체의 N%만 테스트 발송 후 오픈/클릭 성과로 **승자 자동발송**(원자적 claim으로 1회 결정)
- **템플릿** — `{{변수}}` 개인화(발송 시 수신자별 렌더링), 미리보기 API. 콘솔에서 **블록 에디터**(인라인 리치 편집·드래그&드롭·블록별 스타일/배경·이미지 업로드) / 텍스트 / HTML 에디터 제공
- **수신자/리스트** — 연락처 CRUD, CSV 임포트, 리스트 라벨, 구독 상태 관리(전역 억제 + **리스트 단위 옵트아웃** — 멤버십과 분리 기록이라 재임포트에도 유지), 수신자 **활동 타임라인**(발송/오픈/클릭/해지 이력)
- **추적/분석** — 오픈 픽셀 + 클릭 리다이렉트 자체 구현, 캠페인별 오픈·클릭 지표와 **링크별 클릭 랭킹**, 실행 구간(시작~완료) 표시, 시간 버킷으로 집계된 발송 로그, **분석 대시보드**(전환 퍼널·링크 Top·수신자 건강도·요일×시각 오픈 히트맵)
- **수신거부·억제** — 메일 하단 수신거부 링크, suppression 목록이 발송 차단의 단일 진실원
- **바운스 웹훅** — 정규화된 통보 수신, HARD_BOUNCE/COMPLAINT 자동 억제, `X-Mail-Message-Id`로 메시지 상관
- **트랜잭셔널 단건 발송** — 템플릿+변수 즉시 렌더링, 동일 파이프라인 재사용
- **JWT 인증**, 참여 이벤트는 **Kafka 스트림**(`mail.events`)으로 발행 → 워커가 읽기 모델로 프로젝션

## 구조

헥사고날(ports & adapters) — 의존성은 전부 `mail-core`를 향하고, core는 web/JPA/AMQP를 모릅니다.

```
mail-common   공유 DTO/enum (API·프론트 계약)
mail-core     도메인 + 포트 + 유스케이스 (CampaignService, MailDispatchService,
              CampaignScheduleService, TemplateService, ContactService, TrackingService, …)
infra         어댑터: JPA 저장소(Flyway 스키마), RabbitMQ 토폴로지/발행,
              Kafka 이벤트 발행, SMTP/로깅 MailSender, 로컬 파일 스토리지, JWT/BCrypt
mail-api      REST API (8080) — 캠페인/템플릿/수신자/리스트/업로드/추적/웹훅 + JWT 인증
mail-worker   백그라운드 워커 — @RabbitListener 발송/팬아웃 소비, Kafka 이벤트 프로젝션,
              예약 캠페인 릴리서(10초 주기), A/B 승자 스케줄러(30초 주기)
mail-admin    어드민 콘솔 (8081) — 부팅 셸 (확장 예정)
frontend      React 18 + Vite + react-router (5173) — 대시보드/캠페인/분석/템플릿 에디터/수신자/리스트 콘솔
```

### 발송 파이프라인

```
POST /api/campaigns ──▶ PENDING 행 생성 + RabbitMQ 발행 ──▶ 201 즉시 반환
                          (예약이면 발행 보류 → 워커가 시각 도래 시 원자적 claim 후 릴리스)
워커: 메시지 claim(조건부 UPDATE, 이중발송 차단) → 억제 확인 → 개인화 렌더링
      → 추적 링크/픽셀/수신거부 삽입 → SMTP 발송 → SENT/BOUNCED 기록
오픈·클릭·바운스 ──▶ Kafka(mail.events) ──▶ 워커 프로젝션 ──▶ 캠페인 지표
```

상태: 캠페인 `QUEUED → EXPANDING → SENDING → COMPLETED`(EXPANDING은 리스트 팬아웃 동안만, 릴리스 전 예약은 CANCELED 가능), 메시지 `PENDING → SENDING → SENT | FAILED | BOUNCED | SUPPRESSED | CANCELED`. Postgres는 **상태 저장소**이고 큐는 RabbitMQ입니다. 스키마는 Flyway가 소유합니다(`infra/db/migration`).

단계별 구현 해설(한국어): **[docs/logic/](docs/logic/README.md)** · 바운스 설계: [docs/bounce-webhook-design.md](docs/bounce-webhook-design.md)

## 실행

JDK 21 필요. (예: `JAVA_HOME=~/.jdks/corretto-21.x`)

```bash
# 0) 인프라 — Postgres + RabbitMQ + Kafka + MailHog
docker compose up -d          # 초기화는 docker compose down -v

# 1) API (8080) / 2) 워커 — 각각 별도 터미널
./gradlew :mail-api:bootRun
./gradlew :mail-worker:bootRun

# 3) 프론트엔드 콘솔
cd frontend && npm install && npm run dev   # http://localhost:5173

# 빌드/테스트
./gradlew build
```

- 발송된 메일 확인: **MailHog** http://localhost:8025 · 큐 상태: RabbitMQ UI http://localhost:15672 (guest/guest)
- 설정은 전부 환경변수(`${ENV:dev-기본값}`) — 로컬은 무설정 동작, 프로덕션은 [.env.example](.env.example) 참고

### API 직접 호출

```bash
# 회원가입 → 토큰
TOKEN=$(curl -s -X POST localhost:8080/api/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"pass12345","displayName":"Me"}' | jq -r .token)

# 캠페인 생성 (발신자 지정 + 예약 발송 예시 — scheduledAt 생략 시 즉시)
curl -X POST localhost:8080/api/campaigns -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"subject":"뉴스레터 {{name}}님","body":"<p>안녕하세요 {{name}}님</p>",
       "recipients":["a@x.com","b@x.com"],
       "senderName":"Acme 팀","senderEmail":"hello@acme.io",
       "scheduledAt":"2026-08-01T09:00:00Z"}'

# 진행률/지표 폴링 · 집계 발송 로그
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/campaigns/1
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/api/campaigns/1/log"
```

응답의 `total/pending/sent/failed/bounced/suppressed` + `opened/clicked`로 진행·참여를 확인합니다.

## 다음 단계

- 실발송 전환(AWS SES) + SNS 바운스 웹훅: [docs/TODO-ses-sns.md](docs/TODO-ses-sns.md)
- 대용량 처리(부하 측정 → 워커 수평 확장 → fan-out 병목 제거 → throttling): [docs/ROADMAP-scale.md](docs/ROADMAP-scale.md)
