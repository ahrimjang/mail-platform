-- Per-tenant send throttling (token bucket). A workspace may cap its own send
-- rate (msgs/sec) — to match the rate limit of the SMTP provider it brings
-- (BYO), and to keep one tenant's million-message campaign from starving
-- everyone else's queue turns (noisy neighbor). NULL = unlimited.
alter table workspaces add column send_rate_per_sec integer
    constraint chk_workspaces_send_rate check (send_rate_per_sec > 0);

-- Token-bucket state, shared by every worker through the same atomic
-- conditional UPDATE pattern the send/fan-out claims use. One row per
-- rate-limited workspace, created lazily on first acquire. Kept out of the
-- `workspaces` row so the per-message hot-path UPDATE doesn't churn it.
create table workspace_send_buckets (
    workspace_id bigint primary key references workspaces (id),
    tokens       numeric(12,4) not null,
    refilled_at  timestamptz(6) not null
);
