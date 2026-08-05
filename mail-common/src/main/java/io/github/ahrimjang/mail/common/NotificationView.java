package io.github.ahrimjang.mail.common;

import java.time.Instant;

/** 콘솔 알림 한 건 — campaignId 는 클릭 시 이동할 캠페인(소프트 참조). */
public record NotificationView(
        Long id,
        String type,
        String title,
        Long campaignId,
        Instant createdAt,
        Instant readAt
) {
}
