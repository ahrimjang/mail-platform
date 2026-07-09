-- Built-in (example) templates get promoted from frontend code into the DB so
-- they are editable like any other template. builtin_key identifies which seed
-- a row came from (null = user-authored); the API can restore a built-in row
-- to its original content by that key. Rows are inserted by the application
-- seeder at boot, not here — SQL is a poor home for large HTML bodies.
alter table templates add column builtin_key varchar(32);
create unique index uk_templates_builtin_key on templates (builtin_key) where builtin_key is not null;
