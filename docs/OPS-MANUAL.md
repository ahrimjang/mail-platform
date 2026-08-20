# 운영 매뉴얼

Outpace 운영의 일상 절차서. 장애 이력·진단 런북은 [OPS-LOG.md](OPS-LOG.md), 확장 계획은
[ROADMAP-scale.md](ROADMAP-scale.md). 여기는 "평소에 뭘 보고, 뭘 어떻게 하는가"만 담는다.

## 1. 접속 정보

| 대상 | 방법 |
|---|---|
| 서비스 | https://outpacemail.com (Cloudflare → nginx 443) |
| 서버 SSH | `ssh -i ~/.ssh/LightsailDefaultKey-ap-northeast-2.pem ubuntu@15.165.115.152` |
| Grafana | SSH 터널 후 http://localhost:3000 (admin / `.env`의 `GRAFANA_ADMIN_PASSWORD`) |
| MailHog | SSH 터널 후 http://localhost:8025 (SES 전환 전까지의 수신함) |
| DB | 서버에서 `docker compose -f docker-compose.prod.yml exec postgres psql -U maildb maildb` |

SSH 터널(모니터링용 포트 묶음) — PC에서 창을 열어둔 동안만 유효:

```bash
ssh -i C:\Users\user\.ssh\LightsailDefaultKey-ap-northeast-2.pem -L 3000:localhost:3000 -L 8025:localhost:8025 ubuntu@15.165.115.152
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
- (예정) CloudWatch: SES 일 발송량 초과·바운스율 알람 — SES 승인 후 설정

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

### 발송을 당장 전부 멈춰야 할 때 (어뷰즈·평판 사고)
1. **워커만 정지** — 큐는 쌓이고 발송만 멈춘다(가장 부드러움):
   `docker compose -f docker-compose.prod.yml stop worker`
   재개: `docker compose -f docker-compose.prod.yml start worker`
2. **SES 계정 발송 차단**(승인 후 사용 가능) — AWS 쪽에서 전면 중단:
   `aws ses update-account-sending-enabled --no-enabled --region ap-northeast-2`

### 특정 워크스페이스만 발송 정지/해제
자동 정지(바운스율 10%↑)는 `workspaces.sending_suspended_at` 스탬프로 동작. 수동 개입:

```sql
-- 정지:   UPDATE workspaces SET sending_suspended_at = now() WHERE id = <id>;
-- 해제:   UPDATE workspaces SET sending_suspended_at = NULL  WHERE id = <id>;
```

## 6. 운영 레시피

- **베타 정원 조정**: 서버 `.env`의 `APP_BETA_SIGNUP_CAP` 수정 → `up -d` (0 = 무제한)
- **SES 전환**(승인 후 1회): `.env`의 SMTP 주석 6줄 해제 + 자격증명 입력 → `up -d` →
  본인 주소로 테스트 발송 → 받은편지함 도착·헤더의 DKIM/SPF pass 확인 → 이후 mailhog 서비스 제거 가능
- **Grafana 비밀번호 분실**: `grep GRAFANA ~/mail-platform/.env`
- **GitHub 배포 토큰 만료**(90일): 재발급 후 서버에서
  `git remote set-url origin https://<새토큰>@github.com/ahrimjang/mail-platform.git`
- **디스크 정리**(이미지 누적 시): `docker image prune -af` (미사용 빌드 레이어 회수)

## 7. 정기 점검 캘린더

| 주기 | 항목 |
|---|---|
| 매일 | Grafana 대시보드 1분 훑기 |
| 매주 | SES 평판 · Budgets · CPU burst · 서버 자원 |
| 매월 | 스냅샷 복구 가능 여부 눈확인 · `docker image prune` · 의존성 보안 업데이트(`apt upgrade`) |
| 90일 | GitHub 배포 토큰 재발급 |
