# 프로젝트 타임라인

> Outpace 를 처음부터 지금까지 날짜순으로 한 장에 정리한 지도. 자세한 경위는 각 날짜의 워크일지,
> 구현 해설은 [logic/](logic/README.md), 결정의 근거는 링크된 문서에 있다.
> 기준: 2026-09-15, 커밋 154개, Flyway V36. 날짜는 워크일지 기준이고, 워크일지가 없는 날은 커밋 기준이다.

## 한눈에

| 단계 | 기간 | 한 줄 요약 | 대표 산출물 |
|---|---|---|---|
| 1. 발송 파이프라인 기반 | 07-01 ~ 07-08 | 큐에 넣고 바로 응답하고, 워커가 보내는 구조를 세웠다 | RabbitMQ 큐, 원자적 claim, Kafka 이벤트 스트림, Flyway |
| 2. 측정과 기능 확장 | 07-09 ~ 07-20 | 부하 측정으로 병목을 고치고, A/B·분석·멀티테넌시를 붙였다 | 비동기 팬아웃, A/B 승자 발송, 워크스페이스, 발송 속도 제한, Grafana |
| 3. 서비스로 만들기 | 07-23 ~ 08-06 | 과금 모델을 정하고, 가입·보안·평판 방어·배포 구성을 갖췄다 | 발송량 과금, 플랜·결제, 구글 로그인, 평판 가드, 운영 compose |
| 4. 실운영 시작 | 08-20 ~ 09-02 | 실도메인에 자동 배포로 올리고, 첫 장애와 보안 회귀를 겪었다 | GitHub Actions 배포, 운영 매뉴얼·장애 로그, SES 재신청 |
| 5. 베타 전 다듬기 | 09-09 ~ 09-15 | 사용자 동선, 실패 복구, 운영 도구, 대용량 집계를 정비했다 | 바운스 웹훅 실증, 복구 스위퍼, 운영 콘솔, 집계 읽기 모델 |

## 처음 읽는 순서

1. [README.md](../README.md) — 무엇을 하는 서비스이고 어떻게 띄우는가
2. 이 문서 — 어떤 순서로 만들어졌는가
3. [logic/README.md](logic/README.md) — 기능별 구현 해설 13편
4. [RETRO-scaling.md](RETRO-scaling.md) — 처리량 병목을 측정으로 찾아 고친 기록
5. [OPS-MANUAL.md](OPS-MANUAL.md) — 운영 중 무엇을 보고 어떻게 대응하는가
6. [REVIEW-scale.md](REVIEW-scale.md) — 지금의 한계와 다음에 할 일

---

## 1단계 — 발송 파이프라인 기반 (07-01 ~ 07-08)

### 07-01
- 비동기 대량 메일 MVP: 인증, 실제 발송, 오픈·클릭 추적.
- 발송 큐를 RabbitMQ 로 옮기고 바운스 웹훅 처리를 붙였다.
- 관련: [bounce-webhook-design.md](bounce-webhook-design.md) · [logic/02-campaign-queue-rabbitmq.md](logic/02-campaign-queue-rabbitmq.md)

### 07-02
- 템플릿과 `{{변수}}` 개인화, 연락처와 리스트, 프론트 전체, 구현 해설 문서 시작.
- 관련: [logic/06-templates-personalization.md](logic/06-templates-personalization.md) · [logic/07-contacts-lists.md](logic/07-contacts-lists.md)

### 07-03 ~ 07-04
- 상태 저장소를 H2 파일 DB 에서 Postgres 로 옮겼다.
- **중복 발송을 원자적 조건부 UPDATE claim 으로 막았다.** 이후 모든 동시성 문제의 기본 패턴이 된다.
- 코어 단위 테스트 61개로 시작.
- 관련: [logic/03-dispatch-suppression.md](logic/03-dispatch-suppression.md) · [logic/08-unit-tests.md](logic/08-unit-tests.md)

### 07-07
- 오픈·클릭 이벤트를 Kafka 스트림(`mail.events`)으로 분리했다.
- 스키마를 Flyway 로 넘기고, 설정을 전부 환경변수로 뺐다.
- 관련: [logic/04-tracking-analytics.md](logic/04-tracking-analytics.md) · [V1__init.sql](../infra/src/main/resources/db/migration/V1__init.sql)

### 07-08
- 발신자 정보, 예약 발송, 수신자 관리, 발송 로그 집계, 이미지 업로드.
- 디자인 핸드오프대로 콘솔 UI 를 새로 만들었다. 처리량 로드맵을 문서로 세웠다.
- 관련: [ROADMAP-scale.md](ROADMAP-scale.md) · [TODO-ses-sns.md](TODO-ses-sns.md)

## 2단계 — 측정과 기능 확장 (07-09 ~ 07-20)

### 07-09 · [워크일지](worklog/2026-07-09.md)
- k6 부하 측정 하네스를 만들고 병목 두 개를 수치로 찾았다.
- 캠페인 생성이 수신자 수에 비례하던 것을 **비동기 팬아웃**으로 바꿔 상수 시간으로 만들었다.
- 워커 동시성과 완료 판정 쿼리를 고쳐 발송 처리량을 약 7.7배로 올렸다.
- 관련: [loadtest/RESULTS.md](../loadtest/RESULTS.md) · [RETRO-scaling.md](RETRO-scaling.md) 1·2절

### 07-13 ~ 07-14 · [워크일지 07-13](worklog/2026-07-13.md) · [07-14](worklog/2026-07-14.md)
- A/B 테스트: 해시 기반 분배, 안별 지표, 테스트 발송 후 승자 자동 발송.
- 리스트 단위 수신거부를 멤버십 삭제가 아닌 별도 기록으로 재설계했다.
- 관련: [logic/09-ab-testing.md](logic/09-ab-testing.md)

### 07-15 · [워크일지](worklog/2026-07-15.md)
- 대시보드와 분석 탭, 수신자 활동 타임라인, 링크별 클릭.
- 참여도 세그먼트: 오픈·클릭률로 수신자를 골라 보낸다.

### 07-16 · [워크일지](worklog/2026-07-16.md)
- **멀티테넌트 SaaS 전환**: 가입이 곧 워크스페이스, 모든 데이터를 워크스페이스로 격리.
- 수신 동의, 안전장치 묶음, 월 발송량 미터링. SES·SNS 웹훅 코드 완성.
- 메시지 상태 이력 로그를 만들었다가 비용 대비 가치가 낮아 되돌렸다.
- 관련: [logic/10-multitenancy.md](logic/10-multitenancy.md) · [REVIEW-product.md](REVIEW-product.md) · [logic/05-bounce-webhook.md](logic/05-bounce-webhook.md)

### 07-20 · [워크일지](worklog/2026-07-20.md)
- 워크스페이스별 발송 속도 제한: Postgres 토큰 버킷과 TTL 대기 큐.
- Micrometer, Prometheus, Grafana 대시보드.
- 관련: [logic/11-send-throttling.md](logic/11-send-throttling.md) · [logic/12-metrics-grafana.md](logic/12-metrics-grafana.md)

## 3단계 — 서비스로 만들기 (07-23 ~ 08-06)

### 07-23 · [워크일지](worklog/2026-07-23.md)
- 과금 모델 전환: 고객 인프라 연결 방식을 접고 **플랫폼 인프라 + 월 발송량 과금**으로.
- README 에 아키텍처 다이어그램과 확장성 수치표.

### 07-24
- 전체 스택을 컨테이너 이미지와 운영 compose 로 묶었다. Kafka 웹 콘솔 추가.
- 발송량 과금 정책 초안.
- 관련: [BILLING-policy.md](BILLING-policy.md) · [docker-compose.prod.yml](../docker-compose.prod.yml)

### 07-27 · [워크일지](worklog/2026-07-27.md)
- 로그인 무차별 대입 방어. 플랜, 사용량 스냅샷, 결제 스키마.
- 확장 작업 회고와 알려진 한계 정리.
- 관련: [RETRO-scaling.md](RETRO-scaling.md)

### 07-28
- 플랜별 기능 제한, 비밀번호 재설정, 요금제 페이지, 공개 랜딩, 개인정보처리방침과 약관.

### 08-04
- 가입 이메일 인증, 구글 로그인.
- **템플릿과 이메일 분리**: 재사용 서식과 실제 보낼 콘텐츠를 나눴다.
- 워크스페이스별 API 키로 받는 공개 구독 API.

### 08-05
- 인앱 알림, 첫 사용처는 캠페인 발송 완료.
- **평판 가드**: 바운스율 10% 자동 정지, 일회용 도메인 가입 차단, 주소 품질 검증, 신규 워크스페이스 워밍업.
- 발신 도메인 정책과 Reply-To, 베타 가입 정원.

### 08-06
- 운영 배포 구성: 원본 TLS, 운영 환경변수, 서비스별 메모리 한도로 4GB 노드 총량 통제.

## 4단계 — 실운영 시작 (08-20 ~ 09-02)

### 08-20
- 운영 매뉴얼과 장애 로그 문서를 만들었다.
- 보안 헤더, 인증 경로 속도 제한, 모니터링 포트 격리, 모바일 대응.
- **배포 자동화**: master 푸시가 곧 배포.
- 관련: [OPS-MANUAL.md](OPS-MANUAL.md) · [deploy.yml](../.github/workflows/deploy.yml)

### 08-21 · [워크일지](worklog/2026-08-21.md)
- 실도메인에서 자동 배포로 서비스가 돌기 시작했다.
- 첫날 장애 두 건: 원본 접속 차단 설정 중 순단, 배포 중 api 기동 지연이 사이트 전면 다운으로 번짐. 원인을 구조적으로 막았다.
- 관련: [OPS-LOG.md](OPS-LOG.md)

### 08-24
- 운영 DB 를 SSH 터널 전용 루프백 포트로 노출. 공개 문서에서 서버 IP 제거.

### 09-02 · [워크일지](worklog/2026-09-02.md)
- 구글 로그인을 운영에서 실제로 켰다.
- 보안 재검토에서 전날 수정 세 건이 운영에서 효과가 없었음을 찾아 모두 고쳤다.
- SES 프로덕션 액세스 2차 거절, 범위를 줄여 재신청.
- 관련: [TODO-ses-sns.md](TODO-ses-sns.md)

## 5단계 — 베타 전 다듬기 (09-09 ~ 09-16)

### 09-09 · [워크일지](worklog/2026-09-09.md)
- 가입부터 발송까지 사용자 동선을 점검해 여섯 갈래를 고쳤다. 화면이 약속한 동작을 코드가 안 지키던 곳들이다.
- AI 이메일 작성 기능 설계.
- 관련: [ai-compose-design.md](ai-compose-design.md)

### 09-10 · [워크일지](worklog/2026-09-10.md)
- **바운스 웹훅을 실제 반송으로 실증**하고, 원클릭 수신거부 헤더를 실었다.
- 억제 목록 화면, MailHog 제거, CloudWatch SES 경보 3종, 사용 가이드 페이지.

### 09-10 ~ 09-11 · [워크일지](worklog/2026-09-11.md)
- 아키텍처 점검 항목 전부 처리. 주제는 "claim 에 이긴 뒤에 무슨 일이 생기나"였다.
  - 실패 복구: DLQ 리스너, 멈춘 팬아웃·메시지를 다시 넣는 복구 스위퍼.
  - 정확성: claim 조건부 종료 기록, SMTP 타임아웃, 월 한도 예산 검사, A/B 최소 표본, 억제 주소 팬아웃 필터.
  - 운영: 발송 중 중단, 참여도 집계 기간 한정, A/B 분배 해시 교체.
- 구현 해설 문서를 하드닝 이후 동작으로 맞추고 복구·중단 편을 새로 썼다.
- **플랫폼 운영자 콘솔**: 정지·해제, 플랜 조정, 캠페인 중단, 감사 로그를 psql 대신 화면에서.
- 사내 에이전트용 MCP 연동 설계.
- 관련: [logic/13-recovery-and-abort.md](logic/13-recovery-and-abort.md) · [mcp-integration-design.md](mcp-integration-design.md)

### 09-14
- 대용량 트래픽 점검: 적용된 확장 기술, 측정 공백, 위험 순서, 팬아웃 DB 부하.
- 관련: [REVIEW-scale.md](REVIEW-scale.md)

### 09-15
- **캠페인 집계 읽기 모델**: 목록 화면이 캠페인마다 돌리던 집계를 저장된 카운터와 스냅샷으로 바꿨다.
- 관련: [REVIEW-scale.md](REVIEW-scale.md) 3.9절 · [logic/04-tracking-analytics.md](logic/04-tracking-analytics.md) 3-9·3-10절

### 09-16 · [워크일지](worklog/2026-09-16.md)
- **로컬 발송 E2E**: 가입부터 추적까지 한 번에 도는 확인 경로. 인증 토큰도 MailHog 에서 꺼내 쓴다.
- 거기서 찾은 결함 — **시스템 메일에 `From` 헤더가 없었다.** MailHog 는 받아주지만 SES 는 거부하는 메일이라, 운영에서 가입 인증이 막힐 자리였다.
- **검색 노출**: 경로별 메타·OG·구조화 데이터, robots.txt(`/api/` 차단), 사이트맵.

---

## 스키마 변화 한눈에

마이그레이션 번호로 기능이 들어온 순서를 볼 수 있다. 파일은 [db/migration/](../infra/src/main/resources/db/migration).

| 번호 | 들어온 것 |
|---|---|
| V1 ~ V5 | 기본 스키마, 발신자와 예약, 콘텐츠 출처, 예약 취소, 빌트인 템플릿 |
| V6 ~ V7 | 팬아웃 중 상태 `EXPANDING`, 캠페인·상태 복합 인덱스 |
| V8 ~ V11 | A/B 테스트, 승자 발송, 캠페인 이름·설명, 리스트 단위 수신거부 |
| V12 ~ V15 | 완료 시각, 참여도 세그먼트, 수집 기간, 임시저장 |
| V16 ~ V18 | **워크스페이스(멀티테넌시)**, 수신 동의, 등록자 |
| V19 ~ V20 | 발송 속도 제한, 고객 인프라 연결 방식 제거 |
| V21 ~ V24 | 플랜, 월 사용량 스냅샷, 결제, 비밀번호 재설정 |
| V25 ~ V30 | 이메일 인증, 구글 로그인, 이메일·템플릿 분리, 구독 API 키 |
| V31 ~ V33 | 인앱 알림, 발송 자동 정지, Reply-To |
| V34 | 팬아웃 시작 시각 (복구 스위퍼의 고착 판정) |
| V35 | 플랫폼 운영자와 감사 로그 |
| V36 | 캠페인 오픈·클릭 카운터, 끝난 캠페인 상태 스냅샷, 조회용 인덱스 |

## 지금 남은 일

- **처리량**: 운영 노드 재측정, 배치 INSERT 등 — [ROADMAP-scale.md](ROADMAP-scale.md) "2026-09-14 점검에서 추가"
- **운영 콘솔 개시**: 서버 `.env` 에 운영자 이메일 지정 — [OPS-MANUAL.md](OPS-MANUAL.md) 6절
- **운영 스모크**: 가입 인증 메일이 새 시스템 발신 주소로 도착하는지 — [worklog/2026-09-16.md](worklog/2026-09-16.md)
- **검색 등록**: 서치콘솔·서치어드바이저 사이트 등록과 사이트맵 제출
- **다음 기능 후보**: MCP 연동 0단계 — [mcp-integration-design.md](mcp-integration-design.md), AI 작성 1단계 — [ai-compose-design.md](ai-compose-design.md)
