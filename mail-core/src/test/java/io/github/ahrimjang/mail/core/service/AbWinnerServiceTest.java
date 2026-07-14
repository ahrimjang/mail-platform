package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.VariantDelivery;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbWinnerServiceTest {

    private static final long CAMPAIGN_ID = 42L;

    @Mock
    private CampaignRepository campaigns;
    @Mock
    private MailMessageRepository messages;
    @Mock
    private EmailEventRepository events;
    @Mock
    private MailQueue mailQueue;

    @InjectMocks
    private AbWinnerService service;

    private static Campaign dueWinnerFlowCampaign(String metric) {
        Campaign c = Campaign.draft("Hello A", "<p>A</p>");
        c.setId(CAMPAIGN_ID);
        c.setAbSubjectB("Hello B");
        c.setAbSplitPercent(50);
        c.setAbTestPercent(20);
        c.setAbEvalMetric(metric);
        c.setAbEvalWaitMinutes(60);
        c.setAbEvaluateAt(Instant.now().minusSeconds(60));
        return c;
    }

    @Test
    void evaluateDue_openMetric_claimsTheHigherOpenRateVariantAndReleasesHeldMessages() {
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(dueWinnerFlowCampaign("OPEN")));
        when(messages.countByCampaignAndVariant(CAMPAIGN_ID)).thenReturn(List.of(
                new VariantDelivery("A", 100, 100),
                new VariantDelivery("B", 100, 100)));
        // B opens better: 40% vs 20%.
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "A")).thenReturn(20L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "B")).thenReturn(40L);
        when(campaigns.claimAbWinner(CAMPAIGN_ID, "B")).thenReturn(true);
        when(messages.findPendingHeldIdsByCampaign(CAMPAIGN_ID)).thenReturn(List.of(200L, 201L));

        int decided = service.evaluateDue();

        assertThat(decided).isEqualTo(1);
        verify(campaigns).claimAbWinner(CAMPAIGN_ID, "B");
        verify(mailQueue).enqueue(200L);
        verify(mailQueue).enqueue(201L);
    }

    @Test
    void evaluateDue_lostClaim_publishesNothing() {
        // Two winner schedulers polling concurrently: the loser must be a silent no-op.
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(dueWinnerFlowCampaign("OPEN")));
        when(messages.countByCampaignAndVariant(CAMPAIGN_ID)).thenReturn(List.of(
                new VariantDelivery("A", 100, 100),
                new VariantDelivery("B", 100, 100)));
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "A")).thenReturn(20L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "B")).thenReturn(40L);
        when(campaigns.claimAbWinner(CAMPAIGN_ID, "B")).thenReturn(false);

        int decided = service.evaluateDue();

        assertThat(decided).isZero();
        verify(messages, never()).findPendingHeldIdsByCampaign(anyLong());
        verifyNoInteractions(mailQueue);
    }

    @Test
    void evaluateDue_tieFallsBackToVariantA() {
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(dueWinnerFlowCampaign("OPEN")));
        when(messages.countByCampaignAndVariant(CAMPAIGN_ID)).thenReturn(List.of(
                new VariantDelivery("A", 100, 100),
                new VariantDelivery("B", 100, 100)));
        // Identical rates (and the all-zero case degenerates to the same tie).
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "A")).thenReturn(30L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "B")).thenReturn(30L);
        when(campaigns.claimAbWinner(CAMPAIGN_ID, "A")).thenReturn(true);
        when(messages.findPendingHeldIdsByCampaign(CAMPAIGN_ID)).thenReturn(List.of());

        service.evaluateDue();

        verify(campaigns).claimAbWinner(CAMPAIGN_ID, "A");
    }

    @Test
    void evaluateDue_clickMetric_decidesByClickRateNotOpens() {
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(dueWinnerFlowCampaign("CLICK")));
        when(messages.countByCampaignAndVariant(CAMPAIGN_ID)).thenReturn(List.of(
                new VariantDelivery("A", 100, 100),
                new VariantDelivery("B", 100, 100)));
        // B clicks better — opens must not be consulted at all.
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.CLICK, "A")).thenReturn(5L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.CLICK, "B")).thenReturn(15L);
        when(campaigns.claimAbWinner(CAMPAIGN_ID, "B")).thenReturn(true);
        when(messages.findPendingHeldIdsByCampaign(CAMPAIGN_ID)).thenReturn(List.of(300L));

        service.evaluateDue();

        verify(campaigns).claimAbWinner(CAMPAIGN_ID, "B");
        verify(mailQueue).enqueue(300L);
        verify(events, never()).countDistinctMessagesByVariant(anyLong(), eq(EventType.OPEN), anyString());
    }

    @Test
    void evaluateDue_withNothingDue_doesNothing() {
        when(campaigns.findDueForAbEvaluation(any(Instant.class))).thenReturn(List.of());

        assertThat(service.evaluateDue()).isZero();
        verifyNoInteractions(messages, events, mailQueue);
    }
}
