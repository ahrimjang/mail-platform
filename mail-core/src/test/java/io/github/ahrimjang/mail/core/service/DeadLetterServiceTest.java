package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {

    private static final Long CAMPAIGN_ID = 7L;
    private static final Long MESSAGE_ID = 42L;

    @Mock MailMessageRepository messages;
    @Mock CampaignRepository campaigns;
    @Mock MailDispatchService dispatch;
    @Mock NotificationService notifications;

    DeadLetterService service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterService(messages, campaigns, dispatch, notifications);
    }

    private static MailMessage sending() {
        MailMessage m = MailMessage.queued(CAMPAIGN_ID, "user@example.com");
        m.setId(MESSAGE_ID);
        m.setStatus(MessageStatus.SENDING);
        return m;
    }

    private static Campaign campaign() {
        Campaign c = Campaign.draft("Hello", "<p>Body</p>");
        c.setId(CAMPAIGN_ID);
        c.setWorkspaceId(3L);
        return c;
    }

    @Test
    void sendJobDead_marksTheStuckMessageFailed_andFinishesTheCampaign() {
        // DLQ 로 빠진 발송 잡의 메시지는 SENDING 인 채 영영 남았고, 그게 마지막
        // 메시지면 캠페인은 영원히 "발송 중"이었다 — FAILED 확정 + 완료 판정까지 한 세트
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(sending()));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));

        service.sendJobDead(MESSAGE_ID, "mail.send.queue 에서 3회 rejected");

        ArgumentCaptor<MailMessage> saved = ArgumentCaptor.forClass(MailMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(saved.getValue().getErrorMessage()).contains("3회 rejected");
        verify(dispatch).completeIfDrained(CAMPAIGN_ID);
        verify(notifications).campaignSendFailed(any(Campaign.class));
    }

    @Test
    void sendJobDead_leavesAlreadyFinishedMessagesAlone() {
        // 재전달과 DLQ 유입이 겹치면 이미 SENT 일 수 있다 — 성공을 실패로 덮어쓰지 않는다
        MailMessage sent = sending();
        sent.markSent();
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(sent));

        service.sendJobDead(MESSAGE_ID, "x");

        verify(messages, never()).save(any());
        verify(dispatch, never()).completeIfDrained(any());
        verify(notifications, never()).campaignSendFailed(any());
    }

    @Test
    void sendJobDead_missingMessage_isANoop() {
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.empty());

        service.sendJobDead(MESSAGE_ID, "x");

        verify(messages, never()).save(any());
        verify(dispatch, never()).completeIfDrained(any());
    }

    @Test
    void notifiesOncePerCampaign_evenWhenManyMessagesDie() {
        // 포이즌 메시지 1,000건이 알림 1,000건이 되면 안 된다
        when(messages.findById(any())).thenAnswer(inv -> {
            MailMessage m = sending();
            m.setId(inv.getArgument(0));
            return Optional.of(m);
        });
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));

        service.sendJobDead(1L, "r");
        service.sendJobDead(2L, "r");
        service.sendJobDead(3L, "r");

        verify(messages, times(3)).save(any());              // 상태 확정은 건마다
        verify(dispatch, times(3)).completeIfDrained(CAMPAIGN_ID);
        verify(notifications, times(1)).campaignSendFailed(any(Campaign.class));   // 알림은 한 번
    }

    @Test
    void fanoutJobDead_onlyNotifies_neverTouchesState() {
        // 상태 복구(커서 재개)는 스위퍼의 몫 — 여기서는 관측 공백만 메운다
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));

        service.fanoutJobDead(CAMPAIGN_ID, "mail.fanout.queue 에서 3회 rejected");

        verify(notifications).campaignFanoutFailed(any(Campaign.class));
        verify(messages, never()).save(any());
        verify(campaigns, never()).completeIfSending(any());
    }
}
