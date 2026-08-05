package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.NotificationFeedView;
import io.github.ahrimjang.mail.common.NotificationView;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Notification;
import io.github.ahrimjang.mail.core.port.NotificationRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 콘솔 인앱 알림. 발행은 워커 경로에서 일어나므로(캠페인 발송 완료) 발행 메서드는
 * {@link WorkspaceContext} 를 쓰지 않고 캠페인 행에서 워크스페이스를 역해석한다 —
 * ctx 는 콘솔 조회(피드/읽음)에서만 쓴다.
 *
 * <p>중복 발행 방지는 여기서 하지 않는다: 완료 판정의 원자적 UPDATE claim
 * (completeIfSending)에서 이긴 호출자만 이 메서드에 도달하기 때문이다.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final String TYPE_CAMPAIGN_COMPLETED = "CAMPAIGN_COMPLETED";

    private final NotificationRepository notifications;
    private final WorkspaceContext ctx;

    public NotificationService(NotificationRepository notifications, WorkspaceContext ctx) {
        this.notifications = notifications;
        this.ctx = ctx;
    }

    /** 워커 경로: 캠페인 발송 완료 — 실패해도 발송 파이프라인을 막지 않는다. */
    public void campaignCompleted(Campaign campaign) {
        if (campaign.getWorkspaceId() == null) {
            return;   // 격리 이전의 레거시 행 — 보낼 곳이 없다
        }
        try {
            String name = campaign.getName() != null ? campaign.getName() : campaign.getSubject();
            notifications.save(Notification.of(campaign.getWorkspaceId(), TYPE_CAMPAIGN_COMPLETED,
                    "'" + name + "' 캠페인 발송이 완료됐어요.", campaign.getId()));
        } catch (Exception e) {
            log.error("발송 완료 알림 발행 실패: campaign={}", campaign.getId(), e);
        }
    }

    /** 콘솔: 벨 아이콘 피드 — 안 읽은 수 + 최근 20건. */
    public NotificationFeedView feed() {
        return feed(20);
    }

    /** 콘솔: 알림 목록 — 벨은 20건, 전체 페이지는 최대 100건. */
    public NotificationFeedView feed(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        Long workspaceId = ctx.currentWorkspaceId();
        var items = notifications.findRecent(workspaceId, safeLimit).stream()
                .map(n -> new NotificationView(n.getId(), n.getType(), n.getTitle(),
                        n.getCampaignId(), n.getCreatedAt(), n.getReadAt()))
                .toList();
        return new NotificationFeedView(notifications.countUnread(workspaceId), items);
    }

    /** 콘솔: 드롭다운을 여는 순간 전부 읽음 처리. */
    public void markAllRead() {
        notifications.markAllRead(ctx.currentWorkspaceId(), Instant.now());
    }
}
