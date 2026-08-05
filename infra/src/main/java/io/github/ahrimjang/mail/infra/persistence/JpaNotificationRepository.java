package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.Notification;
import io.github.ahrimjang.mail.core.port.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 어댑터: 인앱 알림 포트의 JPA 구현. */
@Repository
public class JpaNotificationRepository implements NotificationRepository {

    private final NotificationJpaRepository jpa;

    public JpaNotificationRepository(NotificationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Notification save(Notification n) {
        NotificationEntity saved = jpa.save(new NotificationEntity(
                n.getId(), n.getWorkspaceId(), n.getType(), n.getTitle(),
                n.getCampaignId(), n.getCreatedAt(), n.getReadAt()));
        n.setId(saved.getId());
        return n;
    }

    @Override
    public List<Notification> findRecent(Long workspaceId, int limit) {
        return jpa.findRecent(workspaceId, PageRequest.of(0, limit)).stream()
                .map(JpaNotificationRepository::toDomain)
                .toList();
    }

    @Override
    public long countUnread(Long workspaceId) {
        return jpa.countByWorkspaceIdAndReadAtIsNull(workspaceId);
    }

    @Override
    @Transactional
    public void markAllRead(Long workspaceId, Instant now) {
        jpa.markAllRead(workspaceId, now);
    }

    private static Notification toDomain(NotificationEntity e) {
        Notification n = new Notification();
        n.setId(e.getId());
        n.setWorkspaceId(e.getWorkspaceId());
        n.setType(e.getType());
        n.setTitle(e.getTitle());
        n.setCampaignId(e.getCampaignId());
        n.setCreatedAt(e.getCreatedAt());
        n.setReadAt(e.getReadAt());
        return n;
    }
}
