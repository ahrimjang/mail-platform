package io.github.ahrimjang.mail.common;

/** 비밀번호 재설정 요청 — 계정 존재 여부와 무관하게 같은 응답을 받는다. */
public record PasswordResetRequest(String email) {
}
