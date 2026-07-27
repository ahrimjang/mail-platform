package io.github.ahrimjang.mail.common;

/** 카드 등록 완료 콜백 — PG 위젯이 successUrl 로 돌려준 authKey. */
public record RegisterCardRequest(String authKey) {
}
