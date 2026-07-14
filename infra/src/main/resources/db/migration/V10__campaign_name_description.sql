-- The console's campaign name/description were UI-only; persist them so lists
-- and detail pages can label campaigns by name instead of subject.
alter table campaigns add column name        varchar(255);
alter table campaigns add column description varchar(1000);
