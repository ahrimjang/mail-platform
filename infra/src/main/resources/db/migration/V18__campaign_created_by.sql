-- Minimal audit: which account registered the campaign. Nullable — legacy rows
-- (and worker-created transactional sends before this column) have no actor.
alter table campaigns add column created_by varchar(255);
