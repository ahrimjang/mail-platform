# TODO — 실제 메일 발송(AWS SES) + 바운스 웹훅(SNS)

> 기능 개발이 얼추 마무리되면 착수. 8월 public 배포의 최대 덩어리이자, "가짜 우체국(MailHog) → 진짜 우체국(SES)" 전환.
> 배경 설계: [bounce-webhook-design.md](bounce-webhook-design.md) (프로바이더별 파싱은 어댑터, 코어는 정규화 이벤트만).

## 왜
- 현재 발송은 `mail-worker → SMTP → MailHog`(로컬 가짜 메일함). 실제 수신자에게 도착하지 않음.
- MailHog은 바운스/컴플레인 통보를 안 보냄 → 지금은 `/api/webhooks/generic`을 curl로 흉내만 냄.
- 배포하려면 실제 발송 + 실제 바운스 유입 경로가 필요.

## 1부: SES 발송 어댑터 (작은 코드, AWS 준비가 대부분)
- [ ] SES SMTP 자격증명 발급 → 환경변수 주입만으로 전환 (코드 변경 최소):
      `SMTP_HOST=email-smtp.<region>.amazonaws.com`, `SMTP_PORT=587`, `SMTP_USERNAME/PASSWORD`,
      `SMTP_AUTH=true`, `SMTP_STARTTLS=true` — 이미 `.env.example`/`application.yml`에 자리 있음.
- [ ] (선택) SMTP 대신 SES API(AWS SDK) 어댑터 — `MailSender` 포트의 새 구현 `SesMailSender`,
      `mail.sender.type=ses`로 스위치. 발송 응답의 SES messageId를 저장해두면 correlation이 더 견고.
- [ ] AWS 준비(코드 아님, 리드타임 있음 — 일찍 신청):
  - [ ] SES 계정 + 발신 도메인 인증(DNS에 SPF/DKIM 레코드)
  - [ ] **샌드박스 해제 심사** 신청 (신규 계정은 검증된 주소로만 발송 가능 — 승인 며칠 소요 가능)

## 2부: SNS 바운스 웹훅 (진짜 코드 덩어리)
경로: 수신자 서버 반송 판정 → SES 감지 → SNS가 우리 서버로 HTTP POST → `POST /api/webhooks/ses`
- [ ] `POST /api/webhooks/ses` 엔드포인트 (SecurityConfig permitAll — 이미 `/api/webhooks/**` 열려 있음)
- [ ] **SNS 구독 확인 처리**: 최초 연결 시 오는 `SubscriptionConfirmation` 타입 → `SubscribeURL` 호출로 자동 승인
- [ ] **SNS 메시지 서명검증**: 공유 시크릿(`X-Webhook-Token`) 대신 아마존 서명 검증 (공개 서버 위조 방지) — 진짜 보안 계층
- [ ] **SES 파서**: SNS 봉투 → 안의 SES Bounce/Complaint/Delivery JSON → 우리 `BounceNotification`으로 정규화
      (`WebhookParser` 어댑터 패턴). Permanent bounce/Complaint → 억제, Transient → 무시.
- [ ] correlation: 발송 시 주입하는 `X-Mail-Message-Id` 헤더가 SES 통보에 echo → 해당 메시지 매칭
      (이미 만들어둔 구조 그대로 재사용).
- [ ] **`BounceService`는 무변경** — 정규화 이벤트만 받으므로. 어댑터/파서/서명검증만 신규.

## 전제/의존
- [ ] 서버가 **공개 URL** 보유해야 SNS가 웹훅을 POST 가능 → 배포 인프라 결정과 맞물림.
- [ ] 로컬 재현 불가 구간 테스트 전략: 실제 SNS 샘플 페이로드를 저장해 파서/서명검증 단위 테스트로 커버.

## 착수 순서 (권장)
1. 코드 먼저 — SES 파서 + SNS 서명검증 + 구독확인 (AWS 없이 샘플 payload로 단위 테스트 가능).
2. AWS 계정/도메인/샌드박스 심사는 병렬로 일찍 신청(리드타임).
3. 배포 후 실제 SNS 구독 연결 → 엔드투엔드 확인.
