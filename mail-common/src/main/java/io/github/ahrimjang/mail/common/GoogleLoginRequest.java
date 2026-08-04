package io.github.ahrimjang.mail.common;

/** 구글 로그인 요청 — Google Identity Services 버튼이 발급한 ID 토큰(JWT). */
public record GoogleLoginRequest(String idToken) {
}
