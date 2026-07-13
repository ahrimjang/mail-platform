# 부하테스트 가이드 (ROADMAP-scale ①·②)

캠페인 1건을 만들어 완료까지 관찰하면서 세 가지 숫자를 얻는다:

| 지표 | 의미 | 무엇의 근거인가 |
|---|---|---|
| `campaign_create_ms` | `POST /api/campaigns` 응답 시간 | 동기 팬아웃 비용 → 로드맵 ③ |
| `drain_msgs_per_sec` | 워커가 큐를 비우는 속도 | 워커 처리량 → ②·④·⑤ |
| `e2e_seconds` | 생성 → COMPLETED 전체 시간 | 사용자 체감 |

측정값은 [RESULTS.md](RESULTS.md)에 날짜와 함께 기록한다.

## 0. 준비 (한 번만)

```bash
winget install --id GrafanaLabs.k6 --silent   # k6 설치 (새 터미널을 열어야 PATH 반영)
./gradlew :mail-worker:bootJar                # 워커 jar — 여러 대 띄울 때 필요
```

## 1. 베이스라인 (워커 1대)

터미널을 나눠 아래를 띄운다:

```bash
docker compose up -d                                      # 인프라
./gradlew :mail-api:bootRun                               # API :8080
java -jar mail-worker/build/libs/mail-worker-1.0-SNAPSHOT.jar   # 워커 1대 (저장소 루트에서)
```

MailHog를 비우고(이중발송 검증의 기준점) 실행:

```bash
curl -X DELETE http://localhost:8025/api/v1/messages
k6 run -e RECIPIENTS=1000 loadtest/baseline.js
k6 run -e RECIPIENTS=5000 loadtest/baseline.js
```

읽는 법: k6 출력 끝의 `CUSTOM` 블록이 위 세 지표다. 콘솔의 `progress:` 줄로 드레인이
일정한 속도인지(뒤로 갈수록 느려지면 큐/DB 문제) 확인한다.

## 2. 워커 수평 확장 검증 (②)

같은 jar를 한 대 더 띄우면 RabbitMQ 경쟁 소비자가 된다:

```bash
java -jar mail-worker/build/libs/mail-worker-1.0-SNAPSHOT.jar   # 두 번째 터미널
```

MailHog를 비우고 같은 부하를 다시:

```bash
curl -X DELETE http://localhost:8025/api/v1/messages
k6 run -e RECIPIENTS=5000 loadtest/baseline.js
# 이중발송 검증 — 정확히 5000이어야 한다 (5001+ = 이중발송, 미만 = 유실)
curl -s "http://localhost:8025/api/v2/messages?limit=1"   # -> {"total":5000,...}
```

기대: 드레인 상승 + `total`이 정확히 N. 원자적 claim(`PENDING→SENDING` 조건부 UPDATE)이
경쟁 소비자를 직렬화하므로 워커를 몇 대를 띄워도 이중발송은 0이어야 한다.

## 3. 변형 실험

- **SMTP 제거(순수 파이프라인 처리량)** — 워커를 로깅 발송기로 실행:
  ```bash
  MAIL_SENDER_TYPE=logging java -jar mail-worker/build/libs/mail-worker-1.0-SNAPSHOT.jar
  ```
  MailHog 수치와의 차이가 곧 SMTP 비용. (2026-07-09 측정에서는 +20%뿐 — 병목은 DB 왕복이었다.)
- **리스너 동시성** — 워커 증설 없이 워커 1대의 소비 스레드를 늘려 비교:
  ```bash
  SPRING_RABBITMQ_LISTENER_SIMPLE_CONCURRENCY=4 java -jar mail-worker/build/libs/mail-worker-1.0-SNAPSHOT.jar
  ```
  서브선형 확장의 원인이 "소비 스레드 부족"인지 "DB 경합"인지 가른다.
- **스케일 키우기** — `-e RECIPIENTS=50000 -e MAX_MINUTES=120`. 10만↑는 MailHog가 메모리에
  전부 들고 있으므로 **logging 발송기 필수**. 생성 타임아웃은 스크립트가 300s로 잡아두었지만
  현재 동기 팬아웃에서는 10만↑ 생성 자체가 수 분 걸린다(그게 ③의 요지).

## 4. 다음 단계 (측정이 가리키는 곳)

1. **③ 팬아웃 비동기화** — 생성 지연이 N에 선형(≈3ms/명)임이 확인됨. 스트리밍/배치 INSERT +
   백그라운드 팬아웃으로 바꾼 뒤 `campaign_create_ms`가 N과 무관해졌는지 이 하네스로 재측정.
2. **드레인 병목 해부** — logging에서도 ~25 msg/s에 그친 원인(메시지당 claim/억제 조회/상태
   COUNT 다발)을 리스너 동시성 실험과 Postgres `pg_stat_statements`로 좁힌다.
3. 개선 하나마다 **같은 커맨드로 재측정 → RESULTS.md에 before/after 한 줄 추가**.
