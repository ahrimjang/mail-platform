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

## 2026-08-06 — 첫 배포 직후 메모리 고갈로 서버 전체 마비

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
