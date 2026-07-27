package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;
import java.time.YearMonth;

/**
 * 월 마감 시점에 고정된 사용량 한 줄 — 청구서의 원천 데이터.
 * 라이브 미터(monthlySent)와 달리 캡처 후 절대 변하지 않는다.
 *
 * @param plan 캡처 시점의 플랜 — 그 달의 청구액(월정액)을 결정
 */
public record UsageSnapshot(
        Long workspaceId,
        YearMonth period,
        long sentCount,
        Plan plan,
        Instant capturedAt
) {
}
