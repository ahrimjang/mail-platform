package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * A tenant workspace as the admin console sees it. {@code monthlySent} 가 발송량
 * 과금의 청구 수치이고, plan/limit 필드는 사용량 카드가 잔여량을 그리는 재료다
 * (null 한도 = 무제한).
 */
public record WorkspaceView(
        Long id,
        String name,
        String plan,
        Long monthlySendLimit,
        Long contactLimit,
        Integer memberLimit,
        Integer sendRateCap,
        Integer sendRatePerSec,
        boolean billingRegistered,
        Instant createdAt,
        long memberCount,
        long monthlySent
) {
}
