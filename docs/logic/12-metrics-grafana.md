# 12. 메트릭 대시보드 — Micrometer → Prometheus → Grafana

## 1. 개요 — 무엇을, 왜

부하테스트(ROADMAP Tier 1)가 "한 번 재는" 도구였다면, 이 스택은 **상시 계기판**입니다.
발송 처리량·큐 적체·스로틀 동작·SMTP 지연이 항상 그래프로 보이므로, 다음 확장성 작업의
전/후 비교를 스크린샷 한 장으로 증명할 수 있습니다.

역할 분담은 셋으로 나뉩니다 — 앱은 숫자를 "내놓기만" 하고, 수집·저장·시각화는 전부
컨테이너 몫입니다:

```
[mail-api :8080]  [mail-worker :8082]  [rabbitmq :15692]
   /actuator/prometheus (텍스트 지표)      /metrics
        ▲                ▲                  ▲
        └────────────────┴──────────────────┘
                 5초마다 긁어감 (pull)
                  [Prometheus :9090]  ← 시계열 저장 + PromQL 질의
                          ▲
                  [Grafana :3000]     ← 프로비저닝된 대시보드가 PromQL 실행
```

## 2. 파일 지도 — 무엇이 어디에

| 파일 | 역할 |
|---|---|
| `monitoring/prometheus.yml` | **수집 대상 목록.** api/worker(호스트라 `host.docker.internal`)와 RabbitMQ. 큐별 깊이는 detailed 엔드포인트를 별도 잡으로 |
| `monitoring/rabbitmq_enabled_plugins` | RabbitMQ 컨테이너에 마운트 — `rabbitmq_prometheus` 플러그인 활성화(:15692) |
| `monitoring/grafana/provisioning/datasources/prometheus.yml` | Grafana가 부팅 때 Prometheus 데이터소스를 자동 등록 |
| `monitoring/grafana/provisioning/dashboards/dashboards.yml` | "대시보드는 이 폴더에서 파일로 읽어라" 선언 |
| `monitoring/grafana/dashboards/mail-platform.json` | **대시보드 본체** (6패널, 손으로 작성 — 3절) |
| `docker-compose.yml` | prometheus/grafana 서비스 + 위 파일들의 마운트 |
| api/worker `application.yml` | actuator 노출(`health,prometheus`) + `application` 태그 + SMTP 히스토그램 버킷 |
| infra 어댑터 3곳 | 도메인 지표 계측 (4절) — `SmtpMailSender`·`JdbcSendRateLimiter`·`RabbitMailQueue` |
| api `SecurityConfig` | `/actuator/**` permitAll (운영은 방화벽/내부 포트 권장) |

전부 **파일 프로비저닝**입니다 — Grafana UI에서 클릭으로 만든 설정이 하나도 없어서,
`docker compose down -v`로 날려도 다시 뜨면 같은 화면이 나오고, 대시보드 변경이 git
diff로 리뷰됩니다.

## 3. 대시보드 JSON 작성법 — 패널을 추가하고 싶다면

`mail-platform.json`은 Grafana의 대시보드 JSON 모델을 **필수 필드만 손으로** 쓴
것입니다(UI Export는 기본값 수백 줄이 섞여 diff가 불가능해짐). 패널 하나의 뼈대:

```json
{
  "id": 7,                              // 대시보드 안에서 유일한 정수
  "type": "timeseries",                 // 카운터/게이지 추이는 전부 timeseries
  "title": "패널 제목",
  "description": "무엇을, 왜 보는지 — 패널 좌상단 ⓘ에 뜸",
  "gridPos": { "x": 0, "y": 24, "w": 12, "h": 8 },   // 24칸 그리드: w12 = 반폭
  "datasource": { "type": "prometheus", "uid": "prometheus" },
  "fieldConfig": { "defaults": { "unit": "ops" }, "overrides": [] },
  "targets": [
    { "refId": "A", "expr": "PromQL 식", "legendFormat": "범례 {{라벨}}" }
  ]
}
```

- `uid`(대시보드 최상위)는 URL(`/d/mail-platform`)이 되므로 바꾸지 말 것.
- `gridPos.y`는 절대좌표 — 새 패널은 마지막 행 아래(y=24)에 붙이면 됩니다.
- `unit`: `ops`(초당 건수) / `s`(시간) / `bytes` — 축 눈금이 사람이 읽게 바뀝니다.
- 저장 후 `docker compose restart grafana` (프로비저닝 파일은 부팅 때 읽음).

**PromQL 작성 규칙** (이 대시보드가 따르는):

1. **카운터는 원값을 그리지 않는다** — 단조증가 곡선은 정보가 없습니다. 항상
   `rate(지표[1m])`로 "초당 증가율"을 그립니다. 1m 창은 5초 scrape 기준 노이즈와
   반응속도의 절충.
2. **지연은 평균 대신 분위수** — `histogram_quantile(0.95, sum(rate(..._bucket[5m])) by (le))`.
   평균은 꼬리 지연(가끔 5초 걸리는 발송)을 숨깁니다. 분위수를 쓰려면 앱 쪽에서
   해당 Timer의 히스토그램을 켜야 합니다(worker yml의
   `management.metrics.distribution.percentiles-histogram.mail.smtp.send: true`).
3. **여러 앱이 내는 같은 지표는 `application` 라벨로 구분** — api/worker yml의
   `management.metrics.tags.application`이 심는 태그입니다.

## 4. 도메인 지표 계측 — 지표를 추가하고 싶다면

JVM·HTTP 지표는 actuator가 공짜로 주지만, "발송이 초당 몇 건인가" 같은 도메인 지표는
직접 심어야 합니다. 원칙: **계측은 어댑터(infra)에만** — mail-core는 Micrometer의
존재도 모릅니다(헥사고날 유지). 세 곳에 심어져 있습니다:

`infra/.../mail/SmtpMailSender.java` — 발송 타이머 (처리량 + 지연을 동시에):
```java
io.micrometer.core.instrument.Timer.Sample sample =
        io.micrometer.core.instrument.Timer.start(io.micrometer.core.instrument.Metrics.globalRegistry);
...
mailSender.send(msg);
stop(sample, "ok");        // mail.smtp.send{outcome="ok"} — rate()가 곧 발송 처리량
...
stop(sample, "error");     // 실패도 같은 지표의 outcome 라벨로
```

`infra/.../persistence/JdbcSendRateLimiter.java` — 스로틀 판정 카운터:
```java
io.micrometer.core.instrument.Metrics.counter("mail.throttle.acquire",
        "granted", String.valueOf(granted)).increment();
```

`infra/.../messaging/RabbitMailQueue.java` — 발행 카운터:
```java
io.micrometer.core.instrument.Metrics.counter("mail.enqueue", "type", type).increment();
// type = send | fanout | throttled
```

- **전역 레지스트리(`Metrics.globalRegistry`)에 기록**합니다. actuator가 있는 앱
  (api/worker)은 Spring Boot가 실제 레지스트리를 전역에 연결해 주고, 없는 앱
  (mail-admin)에서는 조용히 no-op — 생성자 주입 없이 세 앱 모두에서 안전합니다.
- **라벨 카디널리티 주의**: 라벨 값의 종류 수만큼 시계열이 늘어납니다. `outcome`(2종),
  `type`(3종)은 안전하지만, workspaceId·이메일 같은 무한 증가 값은 라벨로 쓰면
  안 됩니다. 테넌트별 스로틀 분해가 필요해지면 상위 N개만 라벨링하는 식으로.
- 새 지표를 추가했다면: Micrometer 이름 `mail.foo.bar`는 Prometheus에서
  `mail_foo_bar_total`(카운터) / `_seconds_*`(타이머)로 바뀝니다 —
  `/actuator/prometheus`를 열어 실제 이름을 확인하고 PromQL을 쓰는 게 빠릅니다.

## 5. 패널 선정 기준 — 왜 이 6개인가

RED(요청 서비스: Rate/Errors/Duration) · USE(자원: Utilization/Saturation/Errors)
방법론을 발송 파이프라인 단계에 대응시킨 결과입니다. 각 패널이 서로 다른 장애 모드
하나를 담당합니다:

| 패널 | 프레임 | 답하는 질문 |
|---|---|---|
| 발송 처리량 (msg/s) | RED-Rate/Errors | 실제로 초당 몇 통 나가나 — **핵심 지표** |
| 큐 깊이 | USE-Saturation | 유입 > 배출인가, DLQ에 쌓였나 |
| 테넌트 스로틀 | 기능 전용 | 토큰버킷(11 문서)이 동작하나, 누가 한도에 부딪히나 |
| SMTP 릴레이 지연 | RED-Duration | 느려지면 릴레이 탓인가 우리 탓인가 |
| API 요청 | RED | 콘솔/API가 느려졌나 |
| JVM 힙 | USE-Utilization | 대량 팬아웃 때 메모리가 버티나 |

GC·스레드풀·커넥션풀은 뺐습니다 — 문제가 보이면 Prometheus에서 직접 쪼개는 드릴다운
영역이고, "한 화면 6패널, 각자 다른 장애 모드"가 계기판의 원칙입니다.

## 6. 확인 방법

```bash
docker compose up -d          # prometheus(9090) + grafana(3000) 포함
./gradlew :mail-api:bootRun & ./gradlew :mail-worker:bootRun

# 1) 원시 지표가 나오는지
curl -s localhost:8080/actuator/prometheus | head    # api
curl -s localhost:8082/actuator/prometheus | head    # worker (메트릭 전용 포트)

# 2) Prometheus 가 4개 타깃을 다 긁는지: http://localhost:9090/targets → 전부 UP

# 3) 대시보드: http://localhost:3000/d/mail-platform (익명 열람)

# 4) 그림을 움직여 보기: 관리 페이지에서 발송 속도 제한 3 저장 → 50명 캠페인 발송
#    → 발송 처리량이 3에서 평평, 스로틀 패널의 거부/재큐 두 선이 함께 상승,
#      큐 깊이에서 mail.send.throttled 출렁임
```

2026-07-20 검증: 타깃 4개 UP, rate=4/s·80건 캠페인에서 토큰 거부 10.5/s ≈ 파킹 재큐
10.7/s(일치 = 정상), 파킹 큐 깊이 18 관측, 80건 전부 SENT.

## 연관 문서
- [11-send-throttling.md](11-send-throttling.md) — 스로틀 패널이 보여주는 그 기능
- [../ROADMAP-scale.md](../ROADMAP-scale.md) — Tier 2 ⑥ 메트릭 대시보드 항목
- [../../loadtest/RESULTS.md](../../loadtest/RESULTS.md) — 부하테스트(일회 측정)와 상보 관계
