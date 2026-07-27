package io.github.ahrimjang.mail.core.port;

/**
 * PG(결제 대행) 포트 — 코어는 "빌링키 발급"과 "빌링키로 청구" 두 동작만 안다.
 * 토스페이먼츠 REST 호출·인증·응답 파싱은 infra 어댑터의 일.
 */
public interface PaymentGateway {

    /**
     * 카드 등록 인증(authKey)을 빌링키로 교환한다.
     * 빌링키는 카드번호 대신 보관하는 결제 수단 식별자 — 이후 청구는 이 키로만.
     *
     * @throws PaymentGatewayException 발급 거절/통신 실패
     */
    String issueBillingKey(String customerKey, String authKey);

    /**
     * 빌링키로 즉시 청구한다.
     *
     * @return PG 측 결제 식별자(paymentKey) — 결제 원장에 기록
     * @throws PaymentGatewayException 승인 거절(한도·정지 카드 등)/통신 실패
     */
    String chargeBilling(String billingKey, String customerKey, int amountKrw,
                         String orderId, String orderName);

    /** PG 호출 실패 — 메시지는 PG가 준 사유(결제 원장의 fail_reason 이 된다). */
    class PaymentGatewayException extends RuntimeException {
        public PaymentGatewayException(String message) {
            super(message);
        }
    }
}
