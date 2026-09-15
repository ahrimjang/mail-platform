-- 캠페인 오픈·클릭 수와 발송 상태 개수를 "조회할 때마다 전량 집계"에서 "저장된 값 읽기"로 바꾼다.
-- 이전: 캠페인 목록이 5초마다 캠페인마다 상태 COUNT 7개 + count(distinct) 2개를 돌렸고, 반복
-- 오픈까지 전부 읽은 뒤 사람 수로 줄였다 (docs/REVIEW-scale.md 3.9).

-- (1) 메시지×이벤트 종류당 최초 참여 1행 — 반복 오픈과 Kafka 재전달은 PK 충돌로 걸러진다.
CREATE TABLE message_engagements (
    message_id  bigint      NOT NULL,
    type        varchar(16) NOT NULL,   -- OPEN | CLICK
    campaign_id bigint      NOT NULL,
    variant     varchar(1),             -- A/B 안 (없으면 null)
    first_at    timestamptz NOT NULL,
    PRIMARY KEY (message_id, type)
);

-- (2) 캠페인×A/B 안 카운터 — (1)에 새 행이 들어갈 때만 같은 문장 안에서 +1 한다.
CREATE TABLE campaign_engagement_counts (
    campaign_id bigint     NOT NULL,
    variant_key varchar(1) NOT NULL,    -- A/B 안, 없으면 '-'
    opened      bigint     NOT NULL DEFAULT 0,
    clicked     bigint     NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, variant_key)
);

-- (3) 끝난 캠페인의 발송 상태 개수 스냅샷. version 은 무효화(늦은 바운스 등)마다 +1 —
-- 세는 도중 무효화가 끼면 저장이 조건부 UPDATE 에서 져서 옛 숫자가 남지 않는다.
CREATE TABLE campaign_delivery_snapshots (
    campaign_id bigint  PRIMARY KEY,
    version     bigint  NOT NULL DEFAULT 0,
    valid       boolean NOT NULL DEFAULT false,
    total       bigint  NOT NULL DEFAULT 0,
    pending     bigint  NOT NULL DEFAULT 0,
    sending     bigint  NOT NULL DEFAULT 0,
    sent        bigint  NOT NULL DEFAULT 0,
    failed      bigint  NOT NULL DEFAULT 0,
    bounced     bigint  NOT NULL DEFAULT 0,
    suppressed  bigint  NOT NULL DEFAULT 0,
    captured_at timestamptz
);

-- 기존 이벤트로 (1)(2) 채우기
INSERT INTO message_engagements (message_id, type, campaign_id, variant, first_at)
SELECT e.message_id, e.type, e.campaign_id, m.variant, min(e.occurred_at)
FROM email_events e
LEFT JOIN mail_messages m ON m.id = e.message_id
WHERE e.type IN ('OPEN', 'CLICK')
GROUP BY e.message_id, e.type, e.campaign_id, m.variant;

INSERT INTO campaign_engagement_counts (campaign_id, variant_key, opened, clicked)
SELECT campaign_id, coalesce(variant, '-'),
       count(*) FILTER (WHERE type = 'OPEN'),
       count(*) FILTER (WHERE type = 'CLICK')
FROM message_engagements
GROUP BY campaign_id, coalesce(variant, '-');

-- 인덱스: 기간 조건(대시보드·히트맵·링크 랭킹·기동 재조정) / 수신자 활동 타임라인 조인.
-- 지금 운영 테이블이 작아 CONCURRENTLY 없이 만든다(Flyway 트랜잭션 안). 수백만 행 이후라면 따로 분리할 것.
CREATE INDEX idx_event_occurred_at ON email_events (occurred_at);
CREATE INDEX idx_event_message ON email_events (message_id);
CREATE INDEX idx_msg_contact ON mail_messages (contact_id) WHERE contact_id IS NOT NULL;
