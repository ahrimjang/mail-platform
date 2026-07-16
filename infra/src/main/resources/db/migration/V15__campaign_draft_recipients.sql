-- Ad-hoc recipients typed into a draft campaign, newline-separated. Real
-- campaigns materialize recipients as mail_messages rows at create time, but a
-- draft has no messages yet, so the raw list must survive the save/resume trip.
alter table campaigns add column draft_recipients text;
