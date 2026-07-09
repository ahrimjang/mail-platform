-- Scheduled campaigns can now be canceled before release: both status enums
-- gain CANCELED, so the V1 CHECK constraints must be recreated to allow it.
alter table campaigns drop constraint campaigns_status_check;
alter table campaigns add constraint campaigns_status_check
    check (status in ('DRAFT', 'QUEUED', 'SENDING', 'COMPLETED', 'CANCELED'));

alter table mail_messages drop constraint mail_messages_status_check;
alter table mail_messages add constraint mail_messages_status_check
    check (status in ('PENDING', 'SENDING', 'SENT', 'FAILED', 'BOUNCED', 'SUPPRESSED', 'CANCELED'));
