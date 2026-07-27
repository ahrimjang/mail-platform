package io.github.ahrimjang.mail.core.service;

/**
 * 플랜 한도 초과 — IllegalStateException 을 상속해 기존 컨트롤러 핸들러가 409 로
 * 매핑한다. 메시지는 화면에 그대로 노출되는 사용자 안내문(한국어).
 */
public class PlanLimitExceededException extends IllegalStateException {

    public PlanLimitExceededException(String message) {
        super(message);
    }
}
