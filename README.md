# mail-platform (POC)

대용량 이메일 발송 서비스의 MVP/POC. 핵심은 **API가 즉시 큐에 적재하고, 워커가 배치로 드레인**하는 비동기 발송 구조입니다.

## 구조

```
mail-common   DTO / enum (CreateCampaignRequest, CampaignView, *Status)
mail-core     도메인(Campaign, MailMessage) + 포트(인터페이스) + 유스케이스
                - CampaignService      : 캠페인 생성 → 수신자 수만큼 큐(PENDING) 적재
                - MailDispatchService  : PENDING 배치를 꺼내 발송, 상태 갱신
infra         포트의 어댑터: JPA 저장소, LoggingMailSender
mail-api      REST API (8080)  : POST/GET /api/campaigns, GET /api/health
mail-worker   큐 폴러         : 2초마다 배치(기본 50건) 발송
mail-admin    어드민 콘솔 (8081) : 부팅 셸 (확장 예정)
frontend      React + Vite (5173) : 캠페인 생성 폼 + 발송 진행률 실시간 표시
```

큐(send queue)는 POC에서 H2 파일 DB(`./data/maildb`, `AUTO_SERVER`)로 구현해 api/worker가 한 큐를 공유합니다. 운영에서는 Postgres/RDS + 별도 MQ로 교체하는 자리입니다.

## 실행

JDK 21 필요. (예: `JAVA_HOME=~/.jdks/corretto-21.x`)

```bash
# 1) API (8080)
./gradlew :mail-api:bootRun

# 2) 워커 (별도 터미널)
./gradlew :mail-worker:bootRun

# 3) 프론트엔드 (별도 터미널)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

> 모든 bootRun은 리포지토리 루트에서 실행되도록 설정되어 있어(`build.gradle.kts`)
> `./data/maildb` 단일 파일을 공유합니다.

### API 직접 호출

```bash
curl -X POST localhost:8080/api/campaigns -H "Content-Type: application/json" \
  -d '{"subject":"뉴스레터","body":"본문","recipients":["a@x.com","b@x.com"]}'

curl localhost:8080/api/campaigns/1     # 발송 진행률 폴링
```

응답의 `total/pending/sent/failed`로 진행 상황을 확인합니다. (주소에 `@`가 없으면 FAILED 처리 — 실패 경로 데모용)

## 다음 단계 (POC 범위 밖)

- `LoggingMailSender` → 실제 SMTP/JavaMail 또는 SES/Sendgrid 어댑터로 교체 (`MailSender` 포트 그대로)
- 큐를 DB 폴링 → SQS/Kafka 등 전용 MQ로, 워커 수평 확장 + 동시성 클레임(`SELECT ... FOR UPDATE SKIP LOCKED`)
- 재시도/백오프, 발송 속도 제한(throttling), 바운스/언서브스크라이브 처리
- 템플릿 변수 치환, 첨부, 인증(SPF/DKIM)
- mail-admin 화면 구현
```
