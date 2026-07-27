package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * 청구 이력 한 줄 — 마감 시점에 고정된 월 사용량과 그 달의 플랜/청구액.
 *
 * @param periodMonth "2026-06" 형식
 * @param amountKrw   그 달 플랜의 월정액(원). null = 협의(엔터프라이즈)
 */
public record UsageSnapshotView(
        String periodMonth,
        long sentCount,
        String plan,
        Integer amountKrw,
        Instant capturedAt
) {
}
