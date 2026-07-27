-- 요금 플랜(BILLING-policy.md): 워크스페이스마다 플랜을 저장하고, 플랜별 한도
-- (월 발송량·연락처·멤버·발송 속도 상한)는 코드(Plan enum)가 소유한다.
-- 가입 즉시 무료 스타터 — 기존 워크스페이스도 스타터로 백필.
alter table workspaces add column plan varchar(16) not null default 'STARTER'
    constraint chk_workspaces_plan check (plan in ('STARTER', 'STANDARD', 'PRO', 'ENTERPRISE'));

-- 속도 상한의 실효화: 토큰버킷은 send_rate_per_sec 가 null 이면 무제한으로 동작하므로,
-- 상한이 있는 플랜에서 미설정을 허용하면 상한이 뚫린다. 기존 워크스페이스를 스타터
-- 상한(5/초)으로 백필하고, 이후 설정 변경은 플랜 상한 이내로만 저장된다(PlanLimits).
update workspaces set send_rate_per_sec = 5 where send_rate_per_sec is null;
