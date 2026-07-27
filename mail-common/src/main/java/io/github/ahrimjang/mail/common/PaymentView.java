package io.github.ahrimjang.mail.common;

import java.time.Instant;

/** 결제 이력 한 줄 — 성공/실패 모두 (failReason 은 실패 시에만). */
public record PaymentView(
        String orderId,
        String plan,
        int amountKrw,
        String status,
        String failReason,
        Instant createdAt
) {
}
