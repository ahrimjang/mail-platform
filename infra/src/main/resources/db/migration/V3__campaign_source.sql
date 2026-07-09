-- Record where a campaign's content and audience came from, so the console can
-- show "which template / which list" on the detail page. Both are soft
-- references: content is snapshotted at create time, so deleting a template or
-- list later must not break existing campaigns (hence no FK constraints).
alter table campaigns add column template_id bigint;
alter table campaigns add column list_id bigint;
