# 운영 매뉴얼

Outpace 운영의 일상 절차서. 장애 이력·진단 런북은 [OPS-LOG.md](OPS-LOG.md), 확장 계획은
[ROADMAP-scale.md](ROADMAP-scale.md). 여기는 "평소에 뭘 보고, 뭘 어떻게 하는가"만 담는다.

## 1. 접속 정보

| 대상 | 방법 |
|---|---|
| 서비스 | https://outpacemail.com (Cloudflare → nginx 443) |
| 서버 SSH | `ssh -i ~/.ssh/LightsailDefaultKey-ap-northeast-2.pem ubuntu@<서버-IP>` |
| Grafana | SSH 터널 후 http://localhost:3000 (admin / `.env`의 `GRAFANA_ADMIN_PASSWORD`) |
| 발송 메일 확인 | SES 실발송 — 본인 주소로 테스트 발송해 받은편지함에서 확인 (MailHog 은 2026-09 SES 전환 후 제거) |
| DB | 서버에서 `docker compose -f docker-compose.prod.yml exec postgres psql -U maildb maildb` |
| **운영 콘솔** | https://outpacemail.com/ops — `APP_PLATFORM_OPERATORS` 에 등록된 계정으로 로그인하면 상단 네비에 "운영" 이 뜬다. 워크스페이스 정지/해제·플랜 조정·API 키 폐기·전 테넌트 캠페인 검색/중단·운영 신호·감사 로그 (2026-09-11부터, psql 대체) |

SSH 터널(모니터링용 포트 묶음) — PC에서 창을 열어둔 동안만 유효:

```bash
ssh -i C:\Users\user\.ssh\LightsailDefaultKey-ap-northeast-2.pem -L 3000:localhost:3000 -L 5432:localhost:5432 ubuntu@<서버-IP>
```

시크릿 소재: 서버 `~/mail-platform/.env`(chmod 600) · TLS 인증서 `~/mail-platform/certs/` ·
SSH 키와 Origin 인증서 사본은 PC `C:\Users\user\.ssh\`. **어느 것도 저장소에 커밋 금지.**

## 2. 모니터링 — 어디서 무엇을 보나

### 매일 1분 (습관)

- **Grafana "메일 플랫폼" 대시보드**: 발송 처리량·실패율·큐 깊이가 평소 모양인지
- **가입/발송 이상 징후**: 대시보드 급증(어뷰즈 가능성)이나 급감(장애 가능성) 모두 신호

### 매주

- **SES 콘솔 → Account dashboard → Reputation**: 바운스율(경고 5%/정지 10%)·컴플레인율(0.1%).
  플랫폼 전체 평판이므로 여기가 나빠지면 모든 테넌트가 영향받는다
- **AWS Budgets**: $40 예산 대비 소진 추이 (80%/100% 이메일 알림이 오면 즉시 원인 확인)
- **Lightsail 콘솔 → Metrics**: CPU burst capacity 잔고 — 지속 소모 중이면 부하 원인 조사
- **서버 자원**: `free -h`(available 500MB↑, 스왑 사용 500MB↓), `df -h /`(80%↓),
  `docker stats --no-stream`(LIMIT 근접 컨테이너 없는지)

### 알림으로 오는 것 (능동 확인 불필요)

- AWS Budgets 80%/100% 초과 — 이메일
- **CloudWatch 경보 3종**(2026-09-10 설정, 서울 리전) — SNS 주제 `outpace-alarms` → 이메일.
  경보·회복(OK) 둘 다 온다. 누락 데이터는 "정상" 처리라 발송이 없는 날엔 조용하다.

  | 경보 | 지표 (AWS/SES 계정 지표) | 조건 | 뜻 |
  |---|---|---|---|
  | SES 바운스율 3% 초과 | `Reputation.BounceRate` 최대/1h | > 0.03 | SES 경고선 5%·정지선 10% 전의 여유 |
  | SES 컴플레인율 초과 | `Reputation.ComplaintRate` 최대/1h | > 0.0008 | SES 경고선 0.1%·정지선 0.5% 전의 여유 |
  | SES 일일 발송 폭주 | `Send` 합계/1d | > 40,000 | 일 할당량 50,000 의 80% — 어뷰즈·루프 감지 |

  **경보 메일이 오면**: 콘솔 `수신자 → 억제 목록`에서 최근 사유별 급증을 확인 → 원인
  워크스페이스를 특정해 5절 "특정 워크스페이스만 발송 정지" → 명단 출처를 사용자에게
  묻는다. 바운스율이 5% 를 넘어가면 5절의 "워커만 정지"로 먼저 멈추고 조사한다.
  ⚠️ 주제 두 개를 혼동하지 말 것 — `outpace-ses-notifications` 는 SES → 우리 웹훅 전용이다.
- **DLQ 유입**(2026-09-10부터 자동 처리) — 발송 잡이 재시도 3회를 소진해 `mail.send.dlq` 로
  빠지면 워커의 DLQ 리스너가 메시지를 FAILED 로 확정하고 캠페인 완료 판정을 돌린 뒤,
  해당 워크스페이스에 **인앱 알림**(캠페인당 1시간에 1회)을 보낸다. 팬아웃 잡이 빠지면
  알림만(상태 복구는 스위퍼 몫). 운영자가 볼 곳: 워커 로그의 `DLQ:` ERROR, 지표
  `mail_dlq_received_total{type=send|fanout|unknown}`(Grafana — 0 보다 크면 조사),
  RabbitMQ UI 의 `mail.send.dlq` 깊이(리스너가 소비하므로 평소 0 이어야 한다).
- **복구 스위퍼**(2026-09-10부터, 워커 60초 주기) — "claim 에 이긴 뒤 죽은" 고착을 자동으로
  걷는다: 10분 넘게 EXPANDING 인 캠페인은 QUEUED 로 되돌려 팬아웃 재발행(이미 만든
  메시지 뒤부터 재개), 릴리스됐는데 잡이 없는 QUEUED 는 팬아웃 재발행 또는(메시지가
  하나도 없으면) CANCELED, 10분 넘게 PENDING/SENDING 인 메시지는 재발행(한 번에 200건).
  지표 `mail_recovery_total{kind}` — **0 이 아닌 값이 반복되면 무언가 계속 죽고 있다는
  뜻**이니 워커 로그의 `복구:` WARN 으로 원인을 본다. 스위퍼 자체는 상태를 새로 만들지
  않고 재발행만 하므로 오탐이어도 발송 결과는 바뀌지 않는다(중복 잡은 claim 에서 진다).

## 3. 배포

현행(수동) — 트래픽 적은 시간대 권장(서버 내 빌드가 메모리를 다툰다):

```bash
cd ~/mail-platform && git pull && docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps   # 전부 Up/healthy 확인
```

- 설정(.env)만 바꿨을 때: `--build` 없이 `up -d` (해당 컨테이너만 재생성)
- 프론트 env(`GOOGLE_CLIENT_ID` 등)는 **빌드 시점 주입** — front 재빌드 필요
- DB 마이그레이션(Flyway V*)은 api 기동 시 자동 적용 — 파괴적 마이그레이션은 스냅샷 확인 후

**롤백**: `git log`로 직전 커밋 확인 → `git checkout <해시> && docker compose -f docker-compose.prod.yml up -d --build`.
(GitHub Actions 전환 후에는 이전 이미지 태그로 즉시 롤백 예정)

## 4. 백업과 복구

- **자동**: Lightsail 일일 스냅샷(03:00 KST 무렵) — 디스크 전체(DB·업로드·인증서·.env 포함)
- **복구**: Lightsail 콘솔 → Snapshots → 해당 시점 → "Create new instance" → 고정 IP를 새
  인스턴스로 재연결(Cloudflare 는 IP 그대로라 무변경). 데이터는 스냅샷 시점으로 돌아간다
- **수동 DB 덤프**(파괴적 작업 직전 보험):

```bash
docker compose -f docker-compose.prod.yml exec postgres pg_dump -U maildb maildb | gzip > ~/maildb-$(date +%F).sql.gz
```

## 5. 긴급 대응

### 서버 전체가 응답 없음 (SSH 불가 포함)
Lightsail 콘솔 → 인스턴스 ⋮ → **Reboot**. 컨테이너는 자동 복구된다.
복구 후 [OPS-LOG.md](OPS-LOG.md) 런북으로 원인 조사 → 항목 기록.

### 특정 캠페인만 멈춰야 할 때 (오발송·잘못된 명단)
콘솔 캠페인 상세의 **발송 중단**(= `POST /api/campaigns/{id}/abort`, 2026-09-11부터).
캠페인을 CANCELED 로 전이하고 남은 PENDING 을 일괄 취소한다 — 팬아웃은 다음 페이지에서
멈추고 디스패치는 취소된 잡을 건너뛴다. 이미 SMTP 로 넘어간 몇 통(워커 동시성 ≤16)은
회수되지 않는다. 사용자가 직접 누를 수 있는 기능이라 운영자 개입 없이도 된다.

**남의 테넌트 캠페인**(어뷰즈 신고 등)은 운영 콘솔 **/ops → 캠페인** 탭에서 검색해 "중단".
같은 조건부 UPDATE 를 타고, 사유가 감사 로그에 남는다.

### 발송을 당장 전부 멈춰야 할 때 (어뷰즈·평판 사고)
1. **워커만 정지** — 큐는 쌓이고 발송만 멈춘다(가장 부드러움):
   `docker compose -f docker-compose.prod.yml stop worker`
   재개: `docker compose -f docker-compose.prod.yml start worker`
2. **SES 계정 발송 차단**(승인 후 사용 가능) — AWS 쪽에서 전면 중단:
   `aws ses update-account-sending-enabled --no-enabled --region ap-northeast-2`

### 특정 워크스페이스만 발송 정지/해제
자동 정지(7일 바운스율 10%↑, 표본 50통↑)는 `workspaces.sending_suspended_at` 스탬프로 동작.
수동 개입은 **운영 콘솔 /ops → 워크스페이스 → 상세 → 발송 정지 / 정지 해제** — 사유 필수,
테넌트에 인앱 알림이 가고 감사 로그에 남는다. 해제 전에 상세 화면의 30일 바운스율과 최근
캠페인의 실패·반송 수를 보고 원인(명단 출처)을 확인한다.

"왜 발송이 정지됐나요" 문의 → /ops 목록에서 소유자 이메일로 검색 → 정지 사유 확인 → 답변.

콘솔이 죽었을 때의 최후 수단만 psql:

```sql
-- 해제:   UPDATE workspaces SET sending_suspended_at = NULL, suspension_reason = NULL WHERE id = <id>;
```

## 6. 운영 레시피

- **베타 정원 조정**: 서버 `.env`의 `APP_BETA_SIGNUP_CAP` 수정 → `up -d` (0 = 무제한)
- **플랫폼 운영자 추가**: `.env`의 `APP_PLATFORM_OPERATORS` 에 이메일 추가(쉼표 구분) → `up -d api`.
  그 계정이 **이미 가입돼 있어야** 부여된다(없으면 api 로그에 경고, 가입 후 재기동). 회수는
  `UPDATE users SET platform_role = NULL WHERE email = '<email>'` — 환경변수에서 빼도 회수되지 않는다.
- **플랜 수동 조정**(보상·체험·엔터프라이즈 계약): /ops → 워크스페이스 상세 → 플랜 변경. 결제 없이
  적용되고 발송 속도 설정이 새 상한으로 클램프된다. 월 마감 청구(`usage_snapshots`)는 그 시점 플랜
  기준이므로 무상 제공이면 청구 전에 되돌릴 것.
- **구독 API 키 유출 신고**: /ops → 워크스페이스 상세 → API 키 폐기. 테넌트는 관리 화면에서 재발급.
- **SMTP 자격증명 교체**(SES 키 로테이션 등): `.env`의 `SMTP_USERNAME/PASSWORD` 수정 → `up -d api worker` →
  본인 주소로 테스트 발송 → 받은편지함 도착·헤더의 DKIM/SPF pass 확인.
  `SMTP_HOST` 가 비면 compose 가 기동을 거부한다(mailhog 폴백 없음 — 2026-09 제거)
- **Grafana 비밀번호 분실**: `grep GRAFANA ~/mail-platform/.env`
- **GitHub 배포 토큰 만료**(90일): 재발급 후 서버에서
  `git remote set-url origin https://<새토큰>@github.com/ahrimjang/mail-platform.git`
- **디스크 정리**(이미지 누적 시): `docker image prune -af` (미사용 빌드 레이어 회수)

## 7. 정기 점검 캘린더

| 주기 | 항목 |
|---|---|
| 매일 | Grafana 대시보드 1분 훑기 · /ops **운영 신호** 탭(정지·진행 중·24시간 실패) |
| 매주 | SES 평판 · Budgets · CPU burst · 서버 자원 |
| 매월 | 스냅샷 복구 가능 여부 눈확인 · `docker image prune` · 의존성 보안 업데이트(`apt upgrade`) |
| 90일 | GitHub 배포 토큰 재발급 |
