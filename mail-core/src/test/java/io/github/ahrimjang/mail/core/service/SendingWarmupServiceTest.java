package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SendingWarmupServiceTest {

    private static final long WS = 7L;

    @Mock MailMessageRepository messages;

    SendingWarmupService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        service = new SendingWarmupService(messages, true);
    }

    @Test
    void smallBatch_alwaysAllowed_withoutQuery() {
        assertThatCode(() -> service.assertBatchAllowed(WS, 50)).doesNotThrowAnyException();
        verify(messages, never()).countSentByWorkspaceSince(anyLong(), any());   // 조회조차 안 한다
    }

    @Test
    void newWorkspace_blockedFromLargeBatch() {
        when(messages.countSentByWorkspaceSince(anyLong(), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.assertBatchAllowed(WS, 1000))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("50명");
    }

    @Test
    void graduatesAfterEnoughSendingHistory() {
        when(messages.countSentByWorkspaceSince(anyLong(), any())).thenReturn(200L);

        assertThatCode(() -> service.assertBatchAllowed(WS, 10_000)).doesNotThrowAnyException();
    }

    @Test
    void disabledFlag_skipsWarmupEntirely() {
        SendingWarmupService off = new SendingWarmupService(messages, false);

        assertThatCode(() -> off.assertBatchAllowed(WS, 100_000)).doesNotThrowAnyException();
        verify(messages, never()).countSentByWorkspaceSince(anyLong(), any());
    }

    @Test
    void status_reportsRemainingWhileWarmingUp() {
        // 작성 화면이 "이번 캠페인은 몇 명까지"를 미리 띄우는 재료 — 집행값과 같아야 한다
        when(messages.countSentByWorkspaceSince(anyLong(), any())).thenReturn(30L);

        SendingWarmupService.Status status = service.statusOf(WS);

        assertThat(status.active()).isTrue();
        assertThat(status.batchLimit()).isEqualTo(50);
        assertThat(status.sentRemaining()).isEqualTo(170);   // 200 - 30
    }

    @Test
    void status_inactiveAfterGraduation() {
        when(messages.countSentByWorkspaceSince(anyLong(), any())).thenReturn(200L);

        assertThat(service.statusOf(WS).active()).isFalse();
    }

    @Test
    void status_inactiveWhenDisabled_withoutQuery() {
        SendingWarmupService off = new SendingWarmupService(messages, false);

        assertThat(off.statusOf(WS).active()).isFalse();
        verify(messages, never()).countSentByWorkspaceSince(anyLong(), any());
    }
}
