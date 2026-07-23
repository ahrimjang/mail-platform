-- 과금 모델 전환(2026-07-20): BYO 커넥터(고객이 자기 SMTP/저장소 계정을 연결해
-- 인프라 비용이 고객에게 직접 청구되는 구조) → 플랫폼이 SMTP/SES·S3를 직접
-- 소유·연동하고, 고객에게는 월 발송량 기준으로 과금하는 구조로.
-- 테넌트별 프로바이더 "선택"은 제품에서 의미가 사라져 컬럼째 제거한다.
-- (선택만 저장되고 실연동은 없던 상태라 잃는 데이터 없음. 경위: docs/ROADMAP-scale.md)
alter table workspaces drop column smtp_provider;
alter table workspaces drop column storage_provider;
