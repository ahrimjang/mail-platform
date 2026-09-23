-- 계정 이메일 정규화 — 대소문자 때문에 로그인이 막히던 문제.
--
-- uk_users_email 은 평범한 UNIQUE (email) 이고 가입·로그인 모두 입력 철자를 그대로 썼다.
-- 그래서 User@x.com 으로 가입한 사람은 user@x.com 으로 로그인하지 못했고(모바일 자동
-- 대문자화로 흔히 밟는 경로), 두 주소가 서로 다른 계정으로 각각 가입될 수 있었다.
-- 앱은 이제 User.normalizeEmail 로 쓰기·조회 양쪽을 소문자로 맞춘다. 기존 행도 같이 내린다.

-- 1) 먼저 막는다 — 소문자로 내렸을 때 겹치는 계정이 있으면 어느 쪽을 살릴지는 사람이
--    판단할 문제다. 조용히 병합하면 한쪽 워크스페이스의 소유자가 사라진다.
DO $$
DECLARE
    conflicts text;
BEGIN
    SELECT string_agg(lower_email, ', ')
      INTO conflicts
      FROM (SELECT lower(email) AS lower_email
              FROM users
             GROUP BY lower(email)
            HAVING count(*) > 1) dup;
    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION
            '대소문자만 다른 중복 계정이 있어 정규화를 중단합니다: % — 어느 계정을 남길지 정한 뒤 수동으로 정리하고 다시 배포하세요.',
            conflicts;
    END IF;
END $$;

-- 2) 실제 정규화. 이미 소문자인 행은 건드리지 않는다.
UPDATE users SET email = lower(trim(email)) WHERE email <> lower(trim(email));

-- 3) 재발 방지. 앱이 정규화하므로 uk_users_email 만으로도 충분하지만, 우회 경로(수동 INSERT,
--    미래의 새 가입 코드)가 같은 버그를 다시 만들지 못하도록 DB 에도 못을 박는다.
CREATE UNIQUE INDEX uk_users_email_lower ON users (lower(email));
