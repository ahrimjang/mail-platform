-- Dispatch's drain check and the dashboard's per-status counts filter on
-- (campaign_id, status); a composite index serves both, and its campaign_id
-- prefix still covers the plain campaign_id lookups the single index did.
drop index if exists idx_msg_campaign;
create index idx_msg_campaign_status on mail_messages (campaign_id, status);
