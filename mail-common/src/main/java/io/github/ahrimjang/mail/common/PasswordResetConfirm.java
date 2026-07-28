package io.github.ahrimjang.mail.common;

/** 재설정 확정 — 메일 링크의 토큰 + 새 비밀번호(8자 이상). */
public record PasswordResetConfirm(String token, String newPassword) {
}
