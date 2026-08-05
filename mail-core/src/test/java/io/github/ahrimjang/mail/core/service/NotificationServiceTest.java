package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.NotificationFeedView;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Notification;
import io.github.ahrimjang.mail.core.port.NotificationRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private static final long WS = 7L;

    @Mock NotificationRepository notifications;
    @Mock WorkspaceContext ctx;

    NotificationService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
        service = new NotificationService(notifications, ctx);
    }

    private static Campaign campaign(Long workspaceId, String name, String subject) {
        Campaign c = new Campaign();
        c.setId(42L);
        c.setWorkspaceId(workspaceId);
        c.setName(name);
        c.setSubject(subject);
        return c;
    }

    @Test
    void campaignCompleted_savesWorkspaceScopedNotification() {
        service.campaignCompleted(campaign(WS, "8월 뉴스레터", "제목"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(saved.capture());
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(WS);
        assertThat(saved.getValue().getCampaignId()).isEqualTo(42L);
        assertThat(saved.getValue().getTitle()).contains("8월 뉴스레터");
        assertThat(saved.getValue().getReadAt()).isNull();   // 새 알림은 안 읽음
    }

    @Test
    void campaignCompleted_fallsBackToSubjectWhenUnnamed() {
        service.campaignCompleted(campaign(WS, null, "제목만 있는 캠페인"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(saved.capture());
        assertThat(saved.getValue().getTitle()).contains("제목만 있는 캠페인");
    }

    @Test
    void campaignCompleted_skipsLegacyRowsWithoutWorkspace() {
        service.campaignCompleted(campaign(null, "레거시", "s"));

        verify(notifications, never()).save(any());
    }

    @Test
    void campaignCompleted_swallowsRepositoryFailure() {
        // 알림 실패가 발송 파이프라인(디스패치/팬아웃)을 죽이면 안 된다
        when(notifications.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.campaignCompleted(campaign(WS, "n", "s")))
                .doesNotThrowAnyException();
    }

    @Test
    void feed_returnsUnreadCountAndRecentItems() {
        Notification n = Notification.of(WS, "CAMPAIGN_COMPLETED", "완료", 42L);
        n.setId(1L);
        when(notifications.findRecent(WS, 20)).thenReturn(List.of(n));
        when(notifications.countUnread(WS)).thenReturn(3L);

        NotificationFeedView feed = service.feed();

        assertThat(feed.unread()).isEqualTo(3);
        assertThat(feed.items()).hasSize(1);
        assertThat(feed.items().get(0).campaignId()).isEqualTo(42L);
    }

    @Test
    void markAllRead_scopesToCurrentWorkspace() {
        service.markAllRead();

        verify(notifications).markAllRead(org.mockito.ArgumentMatchers.eq(WS), any());
    }
}
