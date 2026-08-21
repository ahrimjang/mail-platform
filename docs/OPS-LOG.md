# 운영 로그

운영 중 발생한 장애·이슈를 날짜 역순으로 기록한다. 항목 틀: **증상 → 원인 → 조치 → 재발 방지**.
새 항목은 맨 위에 추가. 사소한 설정 변경은 커밋 메시지로 충분하고, 여기는 "서비스가 이상했던 순간"만.

## 운영 환경 요약

- **서버**: Lightsail 4GB/2vCPU (서울, `outpace-prod`, 고정 IP 15.165.115.152) — 스왑 2GB
- **스택**: `docker compose -f docker-compose.prod.yml` — postgres/rabbitmq/kafka/api/worker/front(nginx) + mailhog(SES 전환 전)/kafka-ui/prometheus/grafana
- **입구**: Cloudflare(Proxy, SSL Full) → nginx 443(Origin 인증서, `certs/`) → api:8080
- **방화벽**: 22/80/443만 개방 — Grafana(3000)·MailHog(8025)는 SSH 터널로만 접근

## 진단 런북 (서버에서)

```bash
# 메모리·디스크·부하 한눈에
free -h && df -h / && uptime

# 컨테이너별 상태·자원 (LIMIT 열이 mem_limit — 여기 근접하면 위험)
docker compose -f docker-compose.prod.yml ps
docker stats --no-stream

# 로그 (서비스명: api / worker / front / postgres ...)
docker compose -f docker-compose.prod.yml logs api --tail 80

# Cloudflare 우회 API 생사 확인
curl -s -i http://localhost/api/health

# 재기동 (설정 반영) / 코드 반영 (pull 후)
docker compose -f docker-compose.prod.yml up -d
cd ~/mail-platform && git pull && docker compose -f docker-compose.prod.yml up -d --build
```

---

## 2026-08-21 — 자동 배포 중 api 기동 지연이 사이트 전면 다운(521)으로 번짐

**증상**: 문의처 교체 커밋의 자동 배포가 "dependency failed to start: api is
unhealthy"로 실패. 이후 https://outpacemail.com 전체가 Cloudflare 521(원본 응답
없음). 다운타임 약 10분.

**원인**: 두 가지가 겹침.
- api 재기동이 이번엔 234초 걸렸다(평소 30초 안팎). 배포 직후엔 이미지 pull +
  구/신 JVM 교대 + 워커 재기동이 2vCPU 를 동시에 짓눌러 기동이 늘어진다.
  헬스체크 예산(start 40s + 15s×10회 ≈ 190초)이 이보다 짧아 unhealthy 판정.
- front(nginx)가 `depends_on: api: service_healthy` — api 판정 실패로 compose 가
  front 기동을 포기했고, nginx 가 없으니 정적 페이지까지 전부 죽었다. worker 도
  같은 조건이라 함께 내려가 있었다(발송 정지 상태였던 셈).

**조치**:
1. 서버에서 `up -d` 재실행 — api 는 그 사이 스스로 healthy 가 됐으므로 front/worker
   즉시 기동, 사이트 복구
2. api 헬스체크 예산을 7분(start 60s + 15s×24회)으로 확대 — 실측 234초의 여유분
3. front 의존을 `service_started` 로 완화 — api 가 늦어도 랜딩·약관은 살아 있고,
   준비 전 /api 만 502
4. worker 도 `service_started` — 마이그레이션 전이면 validate 실패로 재시작을
   반복하다 스키마 준비 즉시 자가 회복(restart: unless-stopped)

**재발 방지**: 배포로 인한 전면 다운 경로 자체가 제거됐다 — 최악의 경우에도
"api 준비될 때까지 API 만 502". 교훈: 단일 노드에서 `depends_on: healthy` 는
"의존이 아픈 동안 나도 죽겠다"는 선언이다. 사용자 대면 서비스(front)에는 쓰지 말 것.
헬스체크 예산은 평시가 아니라 **최악 실측** 기준으로 잡을 것.

**증상**: 배포 당일 가입 요청(`POST /api/auth/signup`)이 Cloudflare 502 반환.
정적 페이지는 정상(랜딩·가입 화면은 뜸). 진단하려던 중 SSH 접속까지 안 됨 —
서버 전체가 응답 불능.

**원인**: JVM 메모리 인식 오류 + 완충지대 부재의 합작.
- Dockerfile의 `-XX:MaxRAMPercentage=75.0`은 "컨테이너 한도의 75%"인데, compose에
  `mem_limit`가 없어서 api·worker JVM이 각각 "호스트 4GB의 75% = 3GB"를 제 몫으로
  인식. Kafka(기본 힙 1G)까지 합쳐 물리 메모리를 초과.
- Lightsail 우분투는 스왑 기본 0B — 메모리가 차는 순간 완충 없이 시스템 전체가 스래싱.

**조치**:
1. Lightsail 콘솔에서 강제 재부팅 → 컨테이너 자동 복구(`restart: unless-stopped`)
2. 스왑 2GB 추가(`/swapfile`, fstab 등록으로 영구화)
3. compose에 서비스별 `mem_limit` 부여(api 768m, worker 640m, kafka 768m+힙 512M 고정,
   kafka-ui 384m+힙 256m, postgres/rabbitmq 512m, 나머지 128~256m) — 커밋 4739231
4. 재기동 후 가입 curl 201 확인, 브라우저 가입 정상

**재발 방지**:
- 이제 한 컨테이너가 폭주해도 자기 한도에서 OOM → 해당 컨테이너만 재시작(격리).
  호스트 마비는 구조적으로 불가.
- 남은 리스크는 서버 내 도커 빌드(Gradle+npm이 운영과 메모리 경합) — GitHub Actions로
  빌드를 옮기면 해소 예정. 그 전까지 빌드는 트래픽 없는 시간대에.
- 교훈: 컨테이너 JVM은 `mem_limit` 없이 올리지 말 것. 단일 노드는 스왑부터 깔 것.
