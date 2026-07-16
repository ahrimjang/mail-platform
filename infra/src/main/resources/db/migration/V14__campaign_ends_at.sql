-- Optional campaign period end: engagement (opens/clicks) observed after this
-- instant is no longer recorded, so the campaign's rates stop moving when its
-- run is over. Null = collect indefinitely.
alter table campaigns add column ends_at timestamptz;
