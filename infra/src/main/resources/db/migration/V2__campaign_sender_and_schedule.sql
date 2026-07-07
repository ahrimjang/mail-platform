-- V2: campaign sender identity + scheduled sending.
--
--   * sender_name / sender_email — optional From override, passed through to the
--     MailSender adapter at dispatch time (null = SMTP default).
--   * scheduled_at — requested send time; null means "send immediately".
--   * enqueued_at  — when the campaign's messages were released to the queue.
--     Immediate campaigns are stamped at create; scheduled ones stay NULL until
--     the worker's scheduler claims them (conditional UPDATE on enqueued_at IS
--     NULL), which is what makes the release exactly-once under concurrency.
--
-- Backfill: every pre-existing campaign was enqueued at creation, so copy
-- created_at into enqueued_at rather than leaving them NULL — otherwise the
-- scheduler would see old campaigns as "never released".

ALTER TABLE campaigns ADD COLUMN sender_name  varchar(255);
ALTER TABLE campaigns ADD COLUMN sender_email varchar(255);
ALTER TABLE campaigns ADD COLUMN scheduled_at timestamptz(6);
ALTER TABLE campaigns ADD COLUMN enqueued_at  timestamptz(6);

UPDATE campaigns SET enqueued_at = created_at WHERE enqueued_at IS NULL;

-- The scheduler polls for due, unreleased campaigns.
CREATE INDEX idx_campaigns_due ON campaigns (scheduled_at) WHERE enqueued_at IS NULL;
