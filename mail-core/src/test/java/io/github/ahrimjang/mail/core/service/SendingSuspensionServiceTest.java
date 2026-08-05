package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.WorkspaceBounceStats;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SendingSuspensionServiceTest {

    private static final long WS = 7L;

    @Mock WorkspaceRepository workspaces;
    @Mock MailMessageRepository messages;

    SendingSuspensionService service;
    Workspace workspace;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        service = new SendingSuspensionService(workspaces, messages);
        workspace = Workspace.of("acme");
        workspace.setId(WS);
        lenient().when(workspaces.findById(WS)).thenReturn(Optional.of(workspace));
        lenient().when(workspaces.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubStats(long attempted, long bounced) {
        when(messages.workspaceBounceStats(anyLong(), any())).thenReturn(
                new WorkspaceBounceStats(attempted, bounced));
    }

    @Test
    void checkAfterBounce_suspendsWhenRateExceedsThreshold() {
        stubStats(100, 15);   // 15% > 10%, 표본 100 ≥ 50

        service.checkAfterBounce(WS);

        assertThat(workspace.isSendingSuspended()).isTrue();
        assertThat(workspace.getSuspensionReason()).contains("15.0%");
    }

    @Test
    void checkAfterBounce_ignoresSmallSamples() {
        stubStats(20, 10);   // 50% 지만 표본 20 < 50 — 소량 발송의 우연은 정지 안 함

        service.checkAfterBounce(WS);

        assertThat(workspace.isSendingSuspended()).isFalse();
        verify(workspaces, never()).save(any());
    }

    @Test
    void checkAfterBounce_ignoresHealthyRates() {
        stubStats(1000, 30);   // 3% < 10%

        service.checkAfterBounce(WS);

        assertThat(workspace.isSendingSuspended()).isFalse();
    }

    @Test
    void checkAfterBounce_idempotentWhenAlreadySuspended() {
        workspace.setSendingSuspendedAt(Instant.now());

        service.checkAfterBounce(WS);

        verify(messages, never()).workspaceBounceStats(anyLong(), any());   // 재판정도 안 한다
    }

    @Test
    void checkAfterBounce_swallowsFailure() {
        // 방어선이 바운스 웹훅 처리 자체를 죽이면 안 된다
        when(messages.workspaceBounceStats(anyLong(), any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.checkAfterBounce(WS)).doesNotThrowAnyException();
    }

    @Test
    void assertNotSuspended_blocksSuspendedWorkspace() {
        workspace.setSendingSuspendedAt(Instant.now());

        assertThatThrownBy(() -> service.assertNotSuspended(WS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정지");
    }

    @Test
    void assertNotSuspended_passesHealthyWorkspace() {
        assertThatCode(() -> service.assertNotSuspended(WS)).doesNotThrowAnyException();
    }
}
