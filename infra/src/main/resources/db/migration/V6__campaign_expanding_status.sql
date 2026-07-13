-- List campaigns fan out asynchronously in the worker, passing through a new
-- EXPANDING state between QUEUED and SENDING. Recreate the campaigns status
-- CHECK (V1/V4 precedent) so the enum value is allowed.
alter table campaigns drop constraint campaigns_status_check;
alter table campaigns add constraint campaigns_status_check
    check (status in ('DRAFT', 'QUEUED', 'EXPANDING', 'SENDING', 'COMPLETED', 'CANCELED'));
