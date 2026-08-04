package io.github.ahrimjang.mail.common;

/** 가입 이메일 인증 확인 요청 — 인증 메일 링크의 토큰. */
public record EmailVerifyConfirm(String token) {
}
