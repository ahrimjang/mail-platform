-- 팬아웃 시작 시각. 팬아웃 도중 워커가 죽으면 캠페인은 EXPANDING 에 영구 고착됐다 —
-- 재전달된 잡은 QUEUED→EXPANDING claim 에서 지고 정상 return 하므로 DLQ 도 안 간다.
-- 복구 스위퍼가 "EXPANDING 인 채 오래된" 캠페인을 골라 QUEUED 로 되돌리고 팬아웃을
-- 재발행하는 판정 기준이다(null 이면 created_at 으로 대신 판정).
ALTER TABLE campaigns ADD COLUMN expanding_started_at timestamptz;
