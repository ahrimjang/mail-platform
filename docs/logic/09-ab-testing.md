# 09. A/B 테스트 — 결정적 분배, 홀드아웃, 승자 자동발송

## 1. 개요 (무엇을, 왜)

캠페인 하나에 **A안/B안 두 가지 내용**을 실어, 성과(오픈율/클릭율)가 좋은 안을 찾는 기능입니다.

1. **변형 B 콘텐츠** — B안 제목/본문을 직접 쓰거나 템플릿에서 스냅샷(`abTemplateId`, 메인 `templateId`와 같은 패턴). B 본문이 비면 제목만 테스트(본문 공통).
2. **결정적 분배** — 수신자 이메일 해시로 변형을 배정. 같은 수신자는 언제 다시 계산해도 같은 변형 → 재시도·재확장이 배정을 못 뒤집는다.
3. **승자 플로우(선택)** — `abTestPercent`를 주면 그 비율만 테스트를 받고, 나머지는 **보류**됐다가 평가 대기 후 성과 좋은 안으로 자동 발송된다.
4. **변형별 지표** — 상세 API의 `variants[]`가 변형별 발송/오픈/클릭을 따로 집계한다(승자 플로우에선 테스트 그룹만).

상태·큐·완료 판정은 기존 파이프라인(02·03 문서)을 그대로 타고, A/B는 그 위에 "누가 어떤 내용을 받는가"만 얹습니다.

## 2. 흐름 (승자 플로우 기준)

```
POST /api/campaigns {abSubjectB, abTestPercent=30, abEvalMetric, abEvalWaitMinutes}
        │
        ▼ 확장(인라인 or 팬아웃) — 수신자마다 해시 배정
   30%: variant A/B  ──▶ 즉시 발행 ──▶ dispatch가 변형별 내용 렌더
   70%: variant NULL ──▶ PENDING인 채 발행 보류  (캠페인은 SENDING에 머묾)
        │
        ▼ 발행 시 ab_evaluate_at = now + 대기시간 스탬프
   (AbWinnerScheduler, 30초 주기)
        │  ab_winner IS NULL AND ab_evaluate_at <= now 인 캠페인
        ▼
   변형별 참여율 비교 ──▶ UPDATE ... SET ab_winner=? WHERE ab_winner IS NULL   ← 원자적 claim
        │                       (스케줄러가 여럿이어도 정확히 1회 결정, 동점→A)
        ▼
   보류분(variant NULL·PENDING) id 발행 ──▶ dispatch: variant NULL + 승자확정 → 승자 내용 렌더
        ▼
   드레인 완료 → COMPLETED (기존 completeIfSending 게이트 그대로)
```

`abTestPercent`가 없으면 승자 단계 없이 전 수신자를 A/B로 나눠 보내는 단순 분할 모드입니다.

## 3. 단계별 실제 코드

### 3-1. 배정 — 해시 버킷, 홀드아웃 포함

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/AbVariantAssigner.java`
```java
    /** @return "B" for roughly splitPercent% of recipients, "A" otherwise. */
    public static String assign(String recipientEmail, int splitPercent) {
        return (bucket(recipientEmail) / 100) < splitPercent ? "B" : "A";
    }

    public static String assignWithHoldout(String recipientEmail, int testPercent, int splitPercent) {
        int bucket = bucket(recipientEmail);
        if (bucket >= testPercent * 100) {
            return null; // holdout — waits for the winner
        }
        return (bucket % 100) < splitPercent ? "B" : "A";
    }

    /** 0 ≤ bucket < 10,000 — 대소문자 무관, 같은 주소면 항상 같은 값. */
    static int bucket(String recipientEmail) {
        byte[] digest = sha256(recipientEmail.trim().toLowerCase(Locale.ROOT));
        long head = 0;
        for (int i = 0; i < 8; i++) {
            head = (head << 8) | (digest[i] & 0xff);
        }
        return (int) Math.floorMod(head, (long) BUCKETS);
    }
```

난수가 아니라 해시라서 **멱등**입니다 — at-least-once 재전달로 확장이 다시 돌아도 같은 사람이 다른 변형을 받을 수 없습니다. 2026-09 이전엔 `String.hashCode()` 버킷이었는데, 비슷한 문자열(같은 도메인·연번 아이디)이 뭉치는 성질 때문에 소표본에서 테스트군이 한쪽 0명이 될 수 있었습니다(ARCH-7). **SHA-256 버킷**은 그 구조를 지워 작은 표본에서도 고르게 퍼집니다. 그래도 표본이 너무 작으면 판정 자체가 무의미하므로, `CampaignService.create` 는 테스트군(대상 × 비율)이 **안별 10명 미만이면 승자 플로우 등록을 거절**하고 대안(비율↑·대상↑·제목 A/B)을 안내합니다.

### 3-2. 확장 — 테스트만 발행, 보류는 PENDING인 채 대기

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/CampaignFanoutService.java`
```java
            List<MailMessage> saved = messages.saveAll(batch);
            // Winner flow only enqueues the test batch: held rows (variant null)
            // stay PENDING until the winner is decided.
            saved.stream()
                    .filter(m -> !campaign.hasWinnerFlow() || m.getVariant() != null)
                    .forEach(m -> mailQueue.enqueue(m.getId()));
```

예약 발송의 "행은 있는데 발행은 나중" 패턴(02 문서 6장)을 재사용한 것입니다. 보류 행이 PENDING이므로 캠페인 완료 게이트(`completeIfSending`)가 자연히 조기 완료를 막습니다 — **완료 판정 코드를 한 줄도 안 고쳤습니다**.

### 3-3. 렌더 — 행 재작성 없이 승자 내용으로

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/MailDispatchService.java`
```java
        // A/B: a held (variant-null) message of a decided campaign renders the winner.
        String variant = message.getVariant();
        if (variant == null && campaign.getAbWinner() != null) {
            variant = campaign.getAbWinner();
        }
        if ("B".equals(variant)) {
            // A/B variant B: swap in the B subject/body where provided (a null B body
            // means a subject-only test — the body stays shared).
            if (campaign.getAbSubjectB() != null) {
                subject = campaign.getAbSubjectB();
            }
            if (campaign.getAbBodyB() != null) {
                bodySrc = campaign.getAbBodyB();
            }
        }
```

승자 확정 시 보류 행 수만 건을 UPDATE하지 않습니다. 발송 시점에 캠페인의 `abWinner`를 읽어 렌더만 바꾸므로 쓰기 비용이 0이고, 부수 효과로 **변형별 지표(variant로 group by)가 자동으로 테스트 그룹만** 셉니다 — 보류분은 영원히 variant NULL이라 비교표를 오염시키지 않습니다.

### 3-4. 승자 판정 — 원자적 claim으로 정확히 1회

`mail-core/src/main/java/io/github/ahrimjang/mail/core/service/AbWinnerService.java`
```java
        for (Campaign campaign : campaigns.findDueForAbEvaluation(now)) {
            Verdict verdict = judge(campaign);
            if (!verdict.conclusive() && !pastDeadline(campaign, now)) {
                campaigns.scheduleAbEvaluation(campaign.getId(), now.plus(DEFER));   // 10분 뒤 다시
                continue;
            }
            String winner = verdict.winner();
            if (!campaigns.claimAbWinner(campaign.getId(), winner)) {
                continue; // another scheduler decided it first
            }
            List<Long> heldIds = messages.findPendingHeldIdsByCampaign(campaign.getId());
            heldIds.forEach(mailQueue::enqueue);
```

```java
    /** 판정 재료를 모아 결론 또는 "아직 이르다"를 낸다. */
    Verdict judge(Campaign campaign) {
        if (messages.hasUnfinishedTestBatch(campaign.getId())) {
            return Verdict.inconclusive("A", "테스트 배치 발송 미완료");
        }
        ... // 변형별 sent / engaged 집계
        String leader = rateB > rateA ? "B" : "A";
        if (sentA < MIN_SENT_PER_VARIANT || sentB < MIN_SENT_PER_VARIANT) {
            return Verdict.inconclusive(leader, "표본 부족 ...");
        }
        if (engagedA + engagedB == 0) {
            return Verdict.inconclusive(leader, "반응 없음 ...");
        }
        if (rateA == rateB) {
            return Verdict.inconclusive(leader, "동률 ...");
        }
        return Verdict.decided(leader);
    }
```

`claimAbWinner`는 `UPDATE ... SET ab_winner=? WHERE id=? AND ab_winner IS NULL` — 발송 claim·팬아웃 claim과 같은 **원자적 조건부 UPDATE** 패턴입니다. 워커가 여러 대여도 승자는 캠페인당 정확히 한 번 정해집니다.

판정에는 **근거 요건**이 있습니다(2026-09, ARCH-6). 테스트 배치에 아직 PENDING/SENDING 이 남았거나, 변형별 발송이 10통 미만이거나, 반응이 하나도 없거나, 동률이면 확정하지 않고 평가를 **10분 뒤로 미룹니다**. 예전엔 이 네 경우가 전부 조용히 "A 승"으로 확정됐고, 속도 제한에 걸린 캠페인은 테스트 배치가 다 나가기도 전에 판정이 돌았습니다. 다만 영원히 미루면 홀드아웃(최대 95%)이 영영 안 나가므로, 릴리스 후 대기 시간 + **24시간**이 지나면 있는 근거로 확정합니다(앞선 쪽, 없으면 A — WARN 로그로 "약한 근거"를 남깁니다).

## 4. 설계 포인트 (왜 이렇게)

- **보류 = 미발행 PENDING, 별도 상태 없음.** 새 메시지 상태를 만들지 않고 "행은 있으나 큐에 안 올림"으로 표현 — 예약 발송과 같은 어휘라 파이프라인의 나머지가 전부 무수정.
- **승자 반영 = 렌더 시점 치환.** 수만 행 UPDATE 대신 읽기 시 분기. 지표 순수성(테스트 그룹만 비교)은 공짜로 따라온다.
- **모든 동시성은 조건부 UPDATE claim.** 배정(해시 멱등) · 판정(`ab_winner IS NULL`) · 발송(기존 PENDING→SENDING claim) — 저장소 불변식 그대로.
- **판정은 근거가 있을 때만, 그러나 무한정 기다리지 않는다.** 배치 미완료·표본 부족·반응 0·동률은 유예(10분), 릴리스 후 24시간이 지나면 확정. 등록 시점에도 테스트군이 안별 10명 미만이면 승자 플로우를 받지 않는다 — 두 하한(`AbWinnerService.MIN_SENT_PER_VARIANT`)은 같은 상수다.
- **한계(알고 둘 것):** `abTemplateId`는 스냅샷만 남기고 id는 저장하지 않아 B안 템플릿 매핑 조회는 불가. 승자 확정 뒤 릴리스 도중 워커가 죽으면 홀드아웃이 PENDING 에 남는데, 이는 복구 스위퍼의 stale 패스가 재발행한다([13 문서](13-recovery-and-abort.md)).

## 5. 확인 방법

```bash
# A/B 승자 플로우 캠페인 (테스트 30%, 오픈율, 대기 1분 — 데모용 최소값)
# 수신자는 70명 이상이어야 한다: 70 × 30% = 21명(안별 10명) 이 등록 하한이다(ARCH-7)
curl -X POST http://localhost:8080/api/campaigns -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "subject":"[A안] 제목","body":"<p>A</p>","recipients":["u1@x.com", "...70명"],
    "abSubjectB":"[B안] 제목","abBodyB":"<p>B</p>",
    "abTestPercent":30,"abEvalMetric":"OPEN","abEvalWaitMinutes":1}'

# 직후: 테스트 그룹만 SENT, 나머지 variant NULL·PENDING
psql> SELECT COALESCE(variant,'(held)'), status, count(*) FROM mail_messages
      WHERE campaign_id=? GROUP BY 1,2;

# B 테스트 메시지의 오픈픽셀을 호출해 B를 이기게 만든 뒤 ~1분+30초 대기
curl http://localhost:8080/api/track/open/{tracking_token}

# 판정 후: ab_winner='B', 보류분이 B안 제목으로 MailHog 도착, 캠페인 COMPLETED
psql> SELECT ab_winner, status FROM campaigns WHERE id=?;
```

화면: 새 캠페인 → "A/B 테스트 사용" 체크(비율 슬라이더·평가 기준·대기 시간, A안/B안 카드), 캠페인 상세 → 변형 비교표 + 승자/대기 배너 + A안/B안 팝업 미리보기, 캠페인 목록 → A/B 컬럼(`B 승`).
