-- 발송 평판 방어: 바운스·컴플레인 비율이 임계를 넘은 워크스페이스의 신규 발송을
-- 자동 정지한다. SES 계정 평판(도메인 전체)이 한 테넌트의 스팸으로 죽는 것을 막는
-- 최후 방어선. 해제는 운영자 확인 후 수동(컬럼 null 로).
ALTER TABLE workspaces ADD COLUMN sending_suspended_at timestamptz;
ALTER TABLE workspaces ADD COLUMN suspension_reason varchar(255);
