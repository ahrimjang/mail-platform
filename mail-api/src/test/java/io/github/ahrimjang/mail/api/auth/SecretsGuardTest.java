package io.github.ahrimjang.mail.api.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretsGuardTest {

    private static final String REAL = "a-real-random-secret-value-not-the-dev-default-000000000000000000";

    /** 기본 인자: 전부 안전한 값(개별 테스트가 필요한 것만 바꿔 넣는다). */
    private static SecretsGuard guard(String jwt, String webhook, String db, String rabbit, boolean require) {
        return new SecretsGuard(jwt, webhook, db, rabbit, require);
    }

    @Test
    void failsStartup_whenRequiredButDefaultsRemain() {
        SecretsGuard g = guard(SecretsGuard.DEV_JWT, SecretsGuard.DEV_WEBHOOK, REAL, REAL, true);

        assertThat(g.offenders()).containsExactly("APP_JWT_SECRET", "APP_WEBHOOK_SECRET");
        assertThatThrownBy(g::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void allowsStartup_whenRealSecretsSet() {
        SecretsGuard g = guard(REAL, REAL, REAL, REAL, true);

        assertThat(g.offenders()).isEmpty();
        assertThatCode(g::check).doesNotThrowAnyException();
    }

    @Test
    void localDev_onlyWarns_evenWithDefaults() {
        // 운영 표식이 없으면(로컬) 기본값이어도 기동은 계속된다 — 무설정 동작 원칙.
        SecretsGuard g = guard(SecretsGuard.DEV_JWT, SecretsGuard.DEV_WEBHOOK, "maildb", "guest", false);

        assertThatCode(g::check).doesNotThrowAnyException();
    }

    @Test
    void catchesInfrastructurePasswords() {
        // DB·큐 기본 비밀번호도 막는다 — postgres 가 루프백으로 열려 있어, 서버에
        // 접근 가능한 누구나 전 테넌트 데이터를 덤프할 수 있게 된다.
        SecretsGuard g = guard(REAL, REAL, "maildb", "guest", true);

        assertThat(g.offenders()).containsExactly("DB_PASSWORD", "RABBITMQ_PASSWORD");
    }

    @Test
    void notFooledByWhitespaceOrWeakOrShortValues() {
        // 기본값 정확 일치만 보면 공백 하나로 우회된다.
        assertThat(guard(" " + SecretsGuard.DEV_JWT + " ", REAL, REAL, REAL, true).offenders())
                .containsExactly("APP_JWT_SECRET");
        // 잘 알려진 약한 값
        assertThat(guard(REAL, "Password", REAL, REAL, true).offenders())
                .containsExactly("APP_WEBHOOK_SECRET");
        // 너무 짧은 값
        assertThat(guard(REAL, REAL, "short", REAL, true).offenders())
                .containsExactly("DB_PASSWORD");
        // 빈 값
        assertThat(guard(REAL, REAL, REAL, "", true).offenders())
                .containsExactly("RABBITMQ_PASSWORD");
    }

    @Test
    void missingProperty_isNotFlagged() {
        // null = 이 앱이 쓰지 않는 설정 — 검사 대상이 아니다.
        assertThat(guard(REAL, REAL, null, null, true).offenders()).isEmpty();
    }
}
