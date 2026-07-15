-- Stamp when a campaign finished draining so the console can show its run
-- window (started = enqueued_at, completed = this). Null for legacy rows and
-- for campaigns still in flight.
alter table campaigns add column completed_at timestamptz;
