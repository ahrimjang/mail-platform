-- A/B winner flow: only ab_test_percent% of the audience receives the test;
-- after ab_eval_wait_minutes the better variant (by ab_eval_metric) is decided
-- into ab_winner and the held-out remainder is sent with it. All nullable —
-- a null ab_test_percent keeps the plain split-only A/B behavior.
alter table campaigns add column ab_test_percent      int;
alter table campaigns add column ab_eval_metric       varchar(8);
alter table campaigns add column ab_eval_wait_minutes int;
alter table campaigns add column ab_evaluate_at       timestamptz;
alter table campaigns add column ab_winner            varchar(1);

create index idx_campaigns_ab_due on campaigns (ab_evaluate_at) where ab_winner is null;
