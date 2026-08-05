package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Notification;

import java.time.Instant;
import java.util.List;

/** 인앱 알림 저장소 포트. */
public interface NotificationRepository {

    Notification save(Notification notification);

    /** 워크스페이스의 최근 알림 — 최신순, 최대 limit 건. */
    List<Notification> findRecent(Long workspaceId, int limit);

    long countUnread(Long workspaceId);

    void markAllRead(Long workspaceId, Instant now);
}
