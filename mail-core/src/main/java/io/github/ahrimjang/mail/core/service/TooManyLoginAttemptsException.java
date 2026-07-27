package io.github.ahrimjang.mail.core.service;

/** 로그인 시도 제한 초과 — HTTP 429로 매핑되고, 재시도 가능 시각까지의 잔여 초를 실어 나른다. */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("too many login attempts; retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
