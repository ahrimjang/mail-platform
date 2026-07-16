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
- [x] `POST /api/webhooks/ses` 엔드포인트 — *2026-07-16 완료.* SNS가 text/plain으로 보내므로 raw body 파싱.
- [x] **SNS 구독 확인 처리** — *완료.* `SnsSubscriptionConfirmer` — SubscribeURL이 https+amazonaws.com일 때만 GET(SSRF 차단).
- [x] **SNS 메시지 서명검증** — *완료.* `SnsSignatureVerifier` — v1(SHA1)/v2(SHA256) RSA, 서명 문자열 규격 구현,
      SigningCertURL은 https+amazonaws.com만 허용, 인증서 캐시. 로컬 데모용 토글 `APP_WEBHOOK_SNS_VERIFY_SIGNATURE`(기본 true).
- [x] **SES 파서** — *완료.* `SesNotificationParser`: Permanent→HARD_BOUNCE, Transient/Undetermined→SOFT_BOUNCE,
      Complaint→COMPLAINT(수신자별 1건), Delivery/unknown→무시. 진단 코드를 사유로 보존.
- [x] correlation — *완료.* SES 통보의 `mail.headers`에서 `X-Mail-Message-Id`(대소문자 무관) 역해석.
      멀티테넌시 규칙과 결합: correlation 없는 통보는 테넌트를 몰라 억제를 버림.
- [x] **`BounceService`는 무변경** — 설계대로 정규화 이벤트만 받음. 신규 코드는 전부 `mail-api/api/webhook/`.

## 전제/의존
- [ ] 서버가 **공개 URL** 보유해야 SNS가 웹훅을 POST 가능 → 배포 인프라 결정과 맞물림.
- [x] 로컬 재현 불가 구간 테스트 — *완료.* 실제 형식 샘플 페이로드로 파서 6케이스, 로컬 RSA 키페어 서명으로
      검증기 4케이스(변조 거부·비아마존 cert URL 거부 포함), 구독확인 3케이스. 로컬 E2E: 샘플 바운스 →
      메시지 BOUNCED + 테넌트 억제 실증.

## 착수 순서 (권장)
1. 코드 먼저 — SES 파서 + SNS 서명검증 + 구독확인 (AWS 없이 샘플 payload로 단위 테스트 가능).
2. AWS 계정/도메인/샌드박스 심사는 병렬로 일찍 신청(리드타임).
3. 배포 후 실제 SNS 구독 연결 → 엔드투엔드 확인.
