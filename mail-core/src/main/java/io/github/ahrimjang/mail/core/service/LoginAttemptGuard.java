package io.github.ahrimjang.mail.core.service;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 브루트포스 방어 — 실패가 누적된 계정·IP를 일시 잠근다.
 *
 * <p>두 계층으로 센다: 계정 키(한 계정을 노리는 사전 공격)와 IP 키(여러 계정을
 * 돌아가며 찌르는 스프레이 공격). 어느 쪽이든 한도를 넘기면 {@link #LOCKOUT} 동안
 * {@link TooManyLoginAttemptsException} 을 던지고, 로그인 성공은 그 계정의 실패
 * 기록을 지운다(IP 기록은 유지 — 스프레이 방어 목적).
 *
 * <p>상태는 인메모리다: 발송 claim 류의 워커 간 공유 상태와 달리 로그인은 api
 * 단일 프로세스가 받으므로 프로세스 로컬로 충분하고, 재시작 시 초기화되는 것도
 * 잠금 장치로는 허용 가능한 소실이다. api 를 수평 확장하는 날이 오면 이 클래스가
 * 포트로 승격되고 Redis/Postgres 구현이 뒤에 붙는다(토큰버킷과 같은 경로).
 */
@Service
public class LoginAttemptGuard {

    /** 같은 계정 연속 실패 허용치 — 넘기면 잠금. */
    static final int MAX_FAILURES_PER_ACCOUNT = 5;
    /** 같은 IP 의 실패 허용치(계정 무관) — 넘기면 잠금. */
    static final int MAX_FAILURES_PER_IP = 20;
    /** 실패 카운트가 유지되는 창 — 이 시간 지나면 처음부터 다시 센다. */
    static final Duration WINDOW = Duration.ofMinutes(15);
    /** 한도 초과 시 잠그는 시간. */
    static final Duration LOCKOUT = Duration.ofMinutes(15);

    private static final int PRUNE_THRESHOLD = 10_000;

    private final Clock clock;
    private final Map<String, Attempts> byKey = new ConcurrentHashMap<>();

    public LoginAttemptGuard() {
        this(Clock.systemUTC());
    }

    LoginAttemptGuard(Clock clock) {
        this.clock = clock;
    }

    /** 로그인 시도 전 호출 — 잠긴 계정/IP 면 비밀번호 검증에 가기 전에 429 로 끊는다. */
    public void checkAllowed(String email, String ip) {
        Instant now = clock.instant();
        assertNotBlocked(accountKey(email), now);
        assertNotBlocked(ipKey(ip), now);
    }

    /** 비밀번호 불일치 등 로그인 실패 시 호출. */
    public void onFailure(String email, String ip) {
        Instant now = clock.instant();
        record(accountKey(email), MAX_FAILURES_PER_ACCOUNT, now);
        record(ipKey(ip), MAX_FAILURES_PER_IP, now);
        pruneIfLarge(now);
    }

    /** 로그인 성공 시 호출 — 그 계정의 실패 이력을 지운다(정상 사용자의 오타 누적 해소). */
    public void onSuccess(String email) {
        byKey.remove(accountKey(email));
    }

    private void assertNotBlocked(String key, Instant now) {
        Attempts a = byKey.get(key);
        if (a != null && a.blockedUntil != null && now.isBefore(a.blockedUntil)) {
            throw new TooManyLoginAttemptsException(
                    Duration.between(now, a.blockedUntil).toSeconds() + 1);
        }
    }

    private void record(String key, int limit, Instant now) {
        byKey.compute(key, (k, a) -> {
            if (a == null || now.isAfter(a.windowStart.plus(WINDOW))) {
                a = new Attempts(now);   // 창이 지났으면 처음부터
            }
            a.failures++;
            if (a.failures >= limit) {
                a.blockedUntil = now.plus(LOCKOUT);
                a.failures = 0;          // 잠금 해제 후엔 다시 처음부터 센다
                a.windowStart = now;
            }
            return a;
        });
    }

    /** 맵 무한 성장 방지 — 커졌을 때만 만료 엔트리를 걷어낸다(로그인은 핫패스가 아님). */
    private void pruneIfLarge(Instant now) {
        if (byKey.size() <= PRUNE_THRESHOLD) {
            return;
        }
        byKey.entrySet().removeIf(e -> {
            Attempts a = e.getValue();
            boolean windowOver = now.isAfter(a.windowStart.plus(WINDOW));
            boolean lockOver = a.blockedUntil == null || now.isAfter(a.blockedUntil);
            return windowOver && lockOver;
        });
    }

    private static String accountKey(String email) {
        return "acct:" + (email == null ? "" : email.toLowerCase());
    }

    private static String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }

    private static final class Attempts {
        int failures;
        Instant windowStart;
        Instant blockedUntil;

        Attempts(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }
}
