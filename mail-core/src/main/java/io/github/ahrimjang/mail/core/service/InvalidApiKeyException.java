package io.github.ahrimjang.mail.core.service;

/** 공개 구독 API 의 키 인증 실패 — 컨트롤러가 401 로 매핑한다. */
public class InvalidApiKeyException extends RuntimeException {

    public InvalidApiKeyException() {
        super("유효하지 않은 API 키입니다.");
    }
}
