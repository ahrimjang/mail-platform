-- A/B split-test campaigns: optional variant-B content and split share on the
-- campaign, and the assigned variant on each queued message. All nullable —
-- a campaign without B content is a plain campaign.
alter table campaigns add column ab_subject_b     varchar(255);
alter table campaigns add column ab_body_b        text;
alter table campaigns add column ab_split_percent int;

alter table mail_messages add column variant varchar(1);
