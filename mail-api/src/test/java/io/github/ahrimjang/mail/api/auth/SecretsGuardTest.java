package io.github.ahrimjang.mail.api.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretsGuardTest {

    private static final String REAL = "a-real-random-secret-value-not-the-dev-default-000000000000000000";

    @Test
    void failsStartup_whenRequiredButDefaultsRemain() {
        SecretsGuard guard = new SecretsGuard(SecretsGuard.DEV_JWT, SecretsGuard.DEV_WEBHOOK, true);

        assertThat(guard.offenders()).containsExactly("APP_JWT_SECRET", "APP_WEBHOOK_SECRET");
        assertThatThrownBy(guard::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void allowsStartup_whenRealSecretsSet() {
        SecretsGuard guard = new SecretsGuard(REAL, REAL, true);

        assertThat(guard.offenders()).isEmpty();
        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    @Test
    void localDev_onlyWarns_evenWithDefaults() {
        // 운영 표식이 없으면(로컬) 기본값이어도 기동은 계속된다 — 무설정 동작 원칙.
        SecretsGuard guard = new SecretsGuard(SecretsGuard.DEV_JWT, SecretsGuard.DEV_WEBHOOK, false);

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    @Test
    void flagsOnlyTheOffendingSecret() {
        SecretsGuard guard = new SecretsGuard(REAL, SecretsGuard.DEV_WEBHOOK, true);

        assertThat(guard.offenders()).containsExactly("APP_WEBHOOK_SECRET");
    }
}
