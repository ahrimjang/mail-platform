package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignScheduleServiceTest {

    @Mock
    private CampaignRepository campaigns;
    @Mock
    private MailMessageRepository messages;
    @Mock
    private MailQueue mailQueue;

    @InjectMocks
    private CampaignScheduleService service;

    private static Campaign scheduled(long id) {
        Campaign c = Campaign.draft("subject", "body");
        c.setId(id);
        c.setScheduledAt(Instant.now().minusSeconds(60));
        return c;
    }

    @Test
    void releaseDue_claimsAndEnqueuesEveryPendingMessageOfDueCampaigns() {
        when(campaigns.findDueForEnqueue(any(Instant.class))).thenReturn(List.of(scheduled(1L)));
        when(campaigns.claimForEnqueue(eq(1L), any(Instant.class))).thenReturn(true);
        when(messages.findPendingIdsByCampaign(1L)).thenReturn(List.of(100L, 101L));

        int released = service.releaseDue();

        assertThat(released).isEqualTo(1);
        verify(mailQueue).enqueue(100L);
        verify(mailQueue).enqueue(101L);
    }

    @Test
    void releaseDue_listCampaign_publishesAFanoutJobInsteadOfPerMessageEnqueues() {
        Campaign listCampaign = scheduled(1L);
        listCampaign.setListId(5L);
        when(campaigns.findDueForEnqueue(any(Instant.class))).thenReturn(List.of(listCampaign));
        when(campaigns.claimForEnqueue(eq(1L), any(Instant.class))).thenReturn(true);

        int released = service.releaseDue();

        assertThat(released).isEqualTo(1);
        verify(mailQueue).enqueueFanout(1L);
        verify(messages, never()).findPendingIdsByCampaign(anyLong());
        verifyNoMoreInteractions(mailQueue);
    }

    @Test
    void releaseDue_winnerFlowCampaign_publishesOnlyTestIdsAndStampsEvaluation() {
        Campaign winnerFlow = scheduled(1L);
        winnerFlow.setAbSubjectB("B subject");
        winnerFlow.setAbSplitPercent(50);
        winnerFlow.setAbTestPercent(20);
        winnerFlow.setAbEvalWaitMinutes(30);
        when(campaigns.findDueForEnqueue(any(Instant.class))).thenReturn(List.of(winnerFlow));
        when(campaigns.claimForEnqueue(eq(1L), any(Instant.class))).thenReturn(true);
        when(messages.findPendingTestIdsByCampaign(1L)).thenReturn(List.of(100L, 101L));

        int released = service.releaseDue();

        assertThat(released).isEqualTo(1);
        // Only the test batch goes out; held rows wait for the winner scheduler.
        verify(mailQueue).enqueue(100L);
        verify(mailQueue).enqueue(101L);
        verifyNoMoreInteractions(mailQueue);
        verify(messages, never()).findPendingIdsByCampaign(anyLong());
        verify(campaigns).scheduleAbEvaluation(eq(1L), any(Instant.class));
    }

    @Test
    void releaseDue_skipsCampaignsWhoseClaimIsLost() {
        // Two schedulers polling concurrently: the losing claim must be a silent no-op.
        when(campaigns.findDueForEnqueue(any(Instant.class))).thenReturn(List.of(scheduled(1L)));
        when(campaigns.claimForEnqueue(eq(1L), any(Instant.class))).thenReturn(false);

        int released = service.releaseDue();

        assertThat(released).isZero();
        verify(messages, never()).findPendingIdsByCampaign(anyLong());
        verifyNoInteractions(mailQueue);
    }

    @Test
    void releaseDue_withNothingDue_doesNothing() {
        when(campaigns.findDueForEnqueue(any(Instant.class))).thenReturn(List.of());

        assertThat(service.releaseDue()).isZero();
        verifyNoInteractions(messages, mailQueue);
    }
}
