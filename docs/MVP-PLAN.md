# mail-platform MVP 기획 (Notifuse 참고)

셀프호스팅 이메일 발송 플랫폼. [Notifuse](https://github.com/Notifuse/notifuse)의 축소판을
기존 POC(Spring 헥사고날 + React, 비동기 큐 엔진) 위에 MVP로 재기획한다.

## 유지할 자산
- **비동기 큐 엔진** (API 적재 → 워커 배치 드레인) — 플랫폼 발송 엔진으로 승격
- **헥사고날 구조** (core=포트, infra=어댑터, api=웹)
- **JWT 인증** (완료)

## 우선순위 결정 (발송·추적/분석 우선)
사용자 우선순위: **실발송 + 추적/분석이 MVP 코어**. 연락처/리스트/템플릿(오디언스·콘텐츠)은 그 뒤.
현재의 *캠페인→메시지→워커* 흐름 위에 발송/트래킹을 먼저 얹는다. 트래킹 성립을 위해 **본문을 HTML로** 취급.

## Notifuse → MVP 스코프

| 영역 | 우리 MVP | 판단 |
|------|----------|------|
| 실발송(SMTP) | **M1** SMTP→MailHog, dev는 Logging 유지 | 코어 |
| HTML 본문 | **M1** | 트래킹 선행조건 |
| 수신거부/억제 | **M1** unsubscribe 토큰 링크 + suppression + bounce | 이메일 필수 |
| 오픈/클릭 트래킹 | **M2** 오픈픽셀 + 클릭 리다이렉트 (자체 구현) | 코어 |
| 분석 대시보드 | **M2** 캠페인별 발송·오픈·클릭·바운스·수신거부 + 비율 | 코어 |
| 트랜잭셔널 API | M3 | 후순위 |
| 템플릿/개인화 | M3 HTML + `{{변수}}` 렌더러 + 미리보기 | 오디언스 계층 |
| 연락처/리스트 | M4 속성(JSON)·멤버십·CSV 임포트 | CRM 계층 |
| 세그먼트·A/B·MJML빌더·멀티프로바이더·워크스페이스·알림센터·블로그·S3·AI | ❌ Post-MVP | 범위 밖 |

## 마일스톤

| M | 목표 | 산출물 |
|---|------|--------|
| **M1 실발송** | 큐 엔진을 진짜 발송으로 | SMTP 어댑터(MailHog, `mail.sender.type`로 분기) · HTML 본문 · unsubscribe 토큰/링크/엔드포인트 · suppression(전역 do-not-send) · 발송실패=BOUNCED→suppression |
| **M2 추적/분석** | 발송을 측정 가능하게 | 오픈픽셀 `/track/open/{token}` · 클릭 리다이렉트 `/track/click/{token}?u=` (링크 재작성) · `EmailEvent` 스트림 · 캠페인 지표 대시보드 |
| **M3 템플릿/개인화** | 재사용·개인화 | `Template` CRUD · `{{변수}}` 렌더러 · 미리보기 · 트랜잭셔널 단건 API |
| **M4 오디언스** | CRM 계층 | `Contact`(속성 JSON) · `List`·멤버십 · CSV 임포트 · 캠페인이 리스트 타깃 |

**M1–M2 = MVP의 심장** (실제 메일 발송 + 오픈·클릭·바운스 집계).

## 설계 원칙
- **status는 발송 결과 전용** (PENDING/SENT/FAILED/BOUNCED/SUPPRESSED). 오픈·클릭 참여지표는
  `EmailEvent` 레코드로 기록해 **집계로 도출** (status 오염 방지, 분석 확장 용이).
- 공개 엔드포인트(`/track/**`, `/unsubscribe/**`)는 SecurityConfig permitAll.
- **알려진 한계 유지**: `findPending` 동시성 클레임 없음(단일 워커 전제), H2 큐 → 수평확장/실MQ는 Post-MVP.

## 도메인 (M1–M2 신규/변경, mail-core)
```
MailMessage   + unsubToken(M1), trackingToken(M2);  status += BOUNCED, SUPPRESSED (M1)
Suppression   email(unique), reason, createdAt                         (M1)
EmailEvent    messageId, campaignId, type(OPEN|CLICK), url?, occurredAt (M2)
Campaign      body를 HTML로 취급 (스키마 무변)
```
신규 포트: `SuppressionRepository`(M1), `EmailEventRepository`(M2). 기존 `MailSender`에 **SMTP 구현** 추가.

## 워커 파이프라인 (M1→M2)
```
PENDING 클레임 → suppression 체크(SUPPRESSED로 스킵)
→ 본문에 [unsubscribe 링크(M1) · 오픈픽셀 주입 + 링크 재작성(M2)]
→ SMTP 발송 → SENT / (실패 시 BOUNCED + suppression 등록)
→ 이후 수신자 행동으로 OPEN/CLICK 이벤트 비동기 기록(M2)
```

## 로컬 실행 (M1 이후)
```
docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog   # SMTP :1025, 웹UI :8025
./gradlew :mail-api:bootRun        # :8080
./gradlew :mail-worker:bootRun     # mail.sender.type=smtp → MailHog로 발송
cd frontend && npm run dev         # :5173
# 발송된 메일은 http://localhost:8025 에서 확인
```
