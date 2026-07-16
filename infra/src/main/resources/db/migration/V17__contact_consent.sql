-- Consent provenance: when and through which channel an address entered the
-- system (MANUAL = typed in by an operator, CSV_IMPORT = bulk import; FORM/API
-- reserved for later). Legacy rows keep null — "no record" is the honest
-- answer for data collected before this column existed, and the console says
-- so instead of inventing a date.
alter table contacts add column consent_source varchar(32);
alter table contacts add column consented_at timestamptz;
