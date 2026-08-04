-- Google 소셜 로그인. 소셜 가입자는 비밀번호가 없으므로 password_hash 를 nullable 로.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- 가입 경로 (LOCAL = 이메일+비밀번호, GOOGLE = 구글). 기존 계정은 전부 LOCAL.
ALTER TABLE users ADD COLUMN auth_provider varchar(16) NOT NULL DEFAULT 'LOCAL';

-- IdP 가 발급한 사용자 고유 식별자 (구글 ID 토큰의 sub). 이메일 변경에도 불변.
ALTER TABLE users ADD COLUMN provider_subject varchar(255);

CREATE UNIQUE INDEX uk_users_provider_subject ON users (provider_subject)
    WHERE provider_subject IS NOT NULL;
