package io.github.ahrimjang.mail.common;

import java.util.List;

/** 벨 아이콘이 그리는 알림 피드 — 안 읽은 수 + 최근 목록 한 번에. */
public record NotificationFeedView(
        long unread,
        List<NotificationView> items
) {
}
