-- 외부 구독 신청 연동용 워크스페이스 API 키. 공개 POST /api/public/subscribe 가
-- X-Api-Key 헤더로 테넌트를 역해석한다 (공개 경로의 토큰→워크스페이스 계보).
ALTER TABLE workspaces ADD COLUMN api_key varchar(64);

CREATE UNIQUE INDEX uk_workspaces_api_key ON workspaces (api_key)
    WHERE api_key IS NOT NULL;
