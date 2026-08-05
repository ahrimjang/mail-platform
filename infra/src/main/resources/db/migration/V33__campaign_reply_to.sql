-- Reply-To 지원. 실운영(SES)에서는 발신 주소가 검증된 서비스 도메인으로 제한되므로,
-- 수신자의 답장이 고객에게 가려면 별도의 회신 주소가 필요하다.
ALTER TABLE campaigns ADD COLUMN reply_to varchar(255);
