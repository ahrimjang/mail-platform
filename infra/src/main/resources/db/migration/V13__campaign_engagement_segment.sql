-- Engagement segment of a list campaign: fan-out only expands members whose
-- open/click rate (distinct engaged over delivered mail) clears these floors,
-- evaluated at fan-out time. Null = no condition (whole list).
alter table campaigns add column seg_min_open_percent int;
alter table campaigns add column seg_min_click_percent int;
