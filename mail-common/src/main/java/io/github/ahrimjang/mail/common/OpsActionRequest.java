package io.github.ahrimjang.mail.common;

/**
 * 플랫폼 운영자 조치 요청 본문 — 정지/해제/중단/키 폐기는 사유가 필수다(감사 로그에
 * 그대로 남고, 정지는 테넌트 알림에도 실린다). 플랜 변경만 {@code plan} 을 함께 쓴다.
 */
public record OpsActionRequest(
        String reason,
        String plan
) {
}
