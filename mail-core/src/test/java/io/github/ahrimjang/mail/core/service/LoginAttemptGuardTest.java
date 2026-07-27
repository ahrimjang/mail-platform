package io.github.ahrimjang.mail.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 시간 축 동작(잠금 해제·창 만료)과 IP 축을 조작 가능한 시계로 검증한다. */
class LoginAttemptGuardTest {

    private static final String IP = "203.0.113.9";

    /** 테스트가 손으로 감는 시계. */
    private Instant now;
    private LoginAttemptGuard guard;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-07-24T12:00:00Z");
        Clock movable = new Clock() {
            @Override public Instant instant() { return now; }
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        };
        guard = new LoginAttemptGuard(movable);
    }

    private void failTimes(String email, String ip, int times) {
        for (int i = 0; i < times; i++) {
            guard.onFailure(email, ip);
        }
    }

    @Test
    void blocksAfterAccountLimit_andUnblocksWhenTheLockoutPasses() {
        failTimes("a@x.com", IP, LoginAttemptGuard.MAX_FAILURES_PER_ACCOUNT);

        assertThatThrownBy(() -> guard.checkAllowed("a@x.com", IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        now = now.plus(LoginAttemptGuard.LOCKOUT).plusSeconds(1);   // 잠금 시간 경과
        assertThatCode(() -> guard.checkAllowed("a@x.com", IP)).doesNotThrowAnyException();
    }

    @Test
    void theFailureWindowExpires_soSlowFailuresNeverLock() {
        // 한도 직전까지 실패 → 창이 지나면 카운트가 처음으로 돌아간다
        failTimes("a@x.com", IP, LoginAttemptGuard.MAX_FAILURES_PER_ACCOUNT - 1);
        now = now.plus(LoginAttemptGuard.WINDOW).plus(Duration.ofSeconds(1));

        failTimes("a@x.com", IP, LoginAttemptGuard.MAX_FAILURES_PER_ACCOUNT - 1);
        assertThatCode(() -> guard.checkAllowed("a@x.com", IP)).doesNotThrowAnyException();
    }

    @Test
    void theIpAxisCatchesASprayAcrossManyAccounts() {
        // 계정을 바꿔가며 찔러도 (계정당 한도 미달) IP 합산 한도가 걸린다
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES_PER_IP; i++) {
            guard.onFailure("user" + i + "@x.com", IP);
        }

        assertThatThrownBy(() -> guard.checkAllowed("fresh@x.com", IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        // 다른 IP 에서는 정상
        assertThatCode(() -> guard.checkAllowed("fresh@x.com", "198.51.100.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void successClearsOnlyTheAccountAxis() {
        failTimes("a@x.com", IP, LoginAttemptGuard.MAX_FAILURES_PER_ACCOUNT - 1);
        guard.onSuccess("a@x.com");

        failTimes("a@x.com", IP, LoginAttemptGuard.MAX_FAILURES_PER_ACCOUNT - 1);
        assertThatCode(() -> guard.checkAllowed("a@x.com", IP)).doesNotThrowAnyException();
    }
}
