package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {

    @Mock CampaignRepository campaigns;
    @Mock MailMessageRepository messages;
    @Mock MailQueue mailQueue;

    RecoveryService service;

    @BeforeEach
    void setUp() {
        service = new RecoveryService(campaigns, messages, mailQueue);
        // 기본: 아무것도 고착돼 있지 않다 — 각 테스트가 필요한 것만 채운다
        when(campaigns.findStuckExpanding(any())).thenReturn(List.of());
        when(campaigns.findOrphanQueued(any())).thenReturn(List.of());
        when(messages.findStaleIds(any(), anyInt())).thenReturn(List.of());
    }

    private static Campaign campaign(long id, Long listId, CampaignStatus status) {
        Campaign c = Campaign.draft("S", "<p>B</p>");
        c.setId(id);
        c.setListId(listId);
        c.setStatus(status);
        c.setEnqueuedAt(Instant.now().minusSeconds(3600));
        return c;
    }

    @Test
    void stuckExpanding_isResetAndFanoutRepublished_onlyByTheCallerThatWonTheReset() {
        // 팬아웃 도중 죽은 캠페인 — 되돌리기에 이긴 호출만 재발행한다(여러 워커 안전)
        when(campaigns.findStuckExpanding(any())).thenReturn(List.of(
                campaign(1L, 5L, CampaignStatus.EXPANDING), campaign(2L, 5L, CampaignStatus.EXPANDING)));
        when(campaigns.resetExpandingToQueued(eq(1L), any())).thenReturn(true);
        when(campaigns.resetExpandingToQueued(eq(2L), any())).thenReturn(false);   // 다른 스위퍼가 먼저

        RecoveryService.Sweep result = service.sweep();

        verify(mailQueue).enqueueFanout(1L);
        verify(mailQueue, never()).enqueueFanout(2L);
        assertThat(result.expandingReset()).isEqualTo(1);
    }

    @Test
    void orphanQueuedListCampaign_getsItsFanoutRepublished() {
        when(campaigns.findOrphanQueued(any())).thenReturn(List.of(campaign(3L, 9L, CampaignStatus.QUEUED)));

        RecoveryService.Sweep result = service.sweep();

        verify(mailQueue).enqueueFanout(3L);
        verify(campaigns, never()).updateStatus(any(), any());
        assertThat(result.orphanFanoutRepublished()).isEqualTo(1);
    }

    @Test
    void orphanQueuedAdHocWithNoMessages_isClosedAsCanceled() {
        // create() 가 저장 뒤 검증에서 실패해 남긴 잔존물 — 보낼 게 없다
        when(campaigns.findOrphanQueued(any())).thenReturn(List.of(campaign(4L, null, CampaignStatus.QUEUED)));
        when(messages.findPendingIdsByCampaign(4L)).thenReturn(List.of());
        when(messages.hasPendingOrSending(4L)).thenReturn(false);

        RecoveryService.Sweep result = service.sweep();

        verify(campaigns).updateStatus(4L, CampaignStatus.CANCELED);
        verify(mailQueue, never()).enqueueFanout(any());
        assertThat(result.orphanCanceled()).isEqualTo(1);
    }

    @Test
    void orphanQueuedAdHocWithMessages_isLeftToTheStalePass() {
        // 메시지가 있으면 캠페인 단위가 아니라 메시지 단위로(3번 패스) 재발행한다
        when(campaigns.findOrphanQueued(any())).thenReturn(List.of(campaign(5L, null, CampaignStatus.QUEUED)));
        when(messages.findPendingIdsByCampaign(5L)).thenReturn(List.of(50L, 51L));

        service.sweep();

        verify(campaigns, never()).updateStatus(any(), any());
        verify(mailQueue, never()).enqueueFanout(any());
    }

    @Test
    void staleMessages_areTouchedThenRepublished() {
        // 재발행 전에 updatedAt 을 갱신해야 다음 스위프에서 곧바로 또 잡히지 않는다
        when(messages.findStaleIds(any(), anyInt())).thenReturn(List.of(100L, 101L, 102L));

        RecoveryService.Sweep result = service.sweep();

        verify(messages).touchPending(eq(List.of(100L, 101L, 102L)), any());
        verify(mailQueue).enqueue(100L);
        verify(mailQueue).enqueue(101L);
        verify(mailQueue).enqueue(102L);
        assertThat(result.staleRepublished()).isEqualTo(3);
    }

    @Test
    void quietSweep_touchesNothing() {
        RecoveryService.Sweep result = service.sweep();

        assertThat(result.total()).isZero();
        verify(messages, never()).touchPending(anyList(), any());
        verify(mailQueue, never()).enqueue(any());
        verify(mailQueue, never()).enqueueFanout(any());
    }
}
