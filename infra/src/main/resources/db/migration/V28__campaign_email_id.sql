-- 캠페인-이메일 매핑. 내용은 등록 시점에 스냅샷되므로(불변) 이 컬럼은 계보
-- 추적용 소프트 참조다 — 이메일이 삭제돼도 캠페인은 그대로다 (template_id 와 동일 구도).
ALTER TABLE campaigns ADD COLUMN email_id bigint;
