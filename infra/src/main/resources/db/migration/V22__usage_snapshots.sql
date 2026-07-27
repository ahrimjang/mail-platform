-- 월 마감 사용량 스냅샷 (BILLING-policy 3절): 청구서는 조회 시점에 재계산되는
-- 라이브 미터가 아니라, 마감 시점에 고정된 이 수치로 발행한다 — 사후 데이터 변동으로
-- 청구액이 흔들리면 분쟁이 되기 때문. 플랜도 캡처 시점 값을 함께 고정한다
-- (청구액 = 그 달 플랜의 월정액).
create table workspace_usage_snapshots (
    workspace_id  bigint not null references workspaces (id),
    period_month  date not null,              -- 해당 월의 1일
    sent_count    bigint not null,
    plan          varchar(16) not null,
    captured_at   timestamptz(6) not null,
    primary key (workspace_id, period_month)  -- 워크스페이스×월 1회 — 캡처 멱등성의 근거
);
