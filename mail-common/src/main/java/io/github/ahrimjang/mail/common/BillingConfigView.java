package io.github.ahrimjang.mail.common;

/**
 * 결제 위젯 초기화에 필요한 공개 설정 — 클라이언트 키는 공개용(서버 시크릿 키와 별개),
 * customerKey 는 워크스페이스에 결정적으로 대응한다.
 */
public record BillingConfigView(
        String clientKey,
        String customerKey,
        boolean billingRegistered
) {
}
