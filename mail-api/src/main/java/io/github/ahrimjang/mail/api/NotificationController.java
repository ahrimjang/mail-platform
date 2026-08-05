package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.NotificationFeedView;
import io.github.ahrimjang.mail.core.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 콘솔 벨 아이콘의 알림 피드 — Bearer 필수, 워크스페이스 스코프. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationFeedView feed(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        return notifications.feed(limit);
    }

    /** 드롭다운을 여는 순간 호출 — 전부 읽음 처리. */
    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll() {
        notifications.markAllRead();
        return ResponseEntity.noContent().build();
    }
}
