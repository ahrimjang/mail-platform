-- 가입 이메일 소유 검증. 미인증 계정은 발송 경로(캠페인 등록·트랜잭셔널)가 잠긴다.
ALTER TABLE users ADD COLUMN email_verified_at timestamptz;

-- 기존 계정은 전부 런치 전 내부 계정이므로 인증된 것으로 백필 —
-- 이 마이그레이션 이후의 신규 가입부터 인증 절차를 탄다.
UPDATE users SET email_verified_at = created_at;

CREATE TABLE email_verification_tokens (
    id          bigserial PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES users (id),
    token       varchar(64) NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL
);

-- 쿨다운 조회(사용자의 최근 발급 시각)용
CREATE INDEX idx_email_verification_tokens_user ON email_verification_tokens (user_id, created_at DESC);
