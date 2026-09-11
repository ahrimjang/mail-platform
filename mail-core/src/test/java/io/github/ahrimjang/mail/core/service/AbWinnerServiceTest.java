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
        c.setEnqueuedAt(Instant.now().minusSeconds(3600));   // 릴리스 1시간 전 — 아직 유예 가능
        return c;
    }

    /** 릴리스 후 대기(60분)+최대 유예(24h)를 넘긴 캠페인 — 근거가 약해도 확정해야 한다. */
    private static Campaign overdueWinnerFlowCampaign(String metric) {
        Campaign c = dueWinnerFlowCampaign(metric);
        c.setEnqueuedAt(Instant.now().minus(java.time.Duration.ofHours(26)));
        return c;
    }

    private void stubDeliveries(long sentA, long sentB) {
        when(messages.countByCampaignAndVariant(CAMPAIGN_ID)).thenReturn(List.of(
                new VariantDelivery("A", sentA, sentA),
                new VariantDelivery("B", sentB, sentB)));
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
    void evaluateDue_tie_isDeferredInsteadOfSilentlyPickingA() {
        // 동률은 근거가 아니다 — 예전엔 조용히 "A 승"으로 확정됐다(ARCH-6)
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(dueWinnerFlowCampaign("OPEN")));
        stubDeliveries(100, 100);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "A")).thenReturn(30L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "B")).thenReturn(30L);

        int decided = service.evaluateDue();

        assertThat(decided).isZero();
        verify(campaigns).scheduleAbEvaluation(eq(CAMPAIGN_ID), any(Instant.class));
        verify(campaigns, never()).claimAbWinner(anyLong(), anyString());
        verifyNoInteractions(mailQueue);
    }

    @Test
    void evaluateDue_unfinishedTestBatch_isDeferredBeforeLookingAtRates() {
        // 속도 제한에 걸려 테스트 배치가 다 안 나갔는데 판정하면 늦게 나간 쪽이 불리하다
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(dueWinnerFlowCampaign("OPEN")));
        when(messages.hasUnfinishedTestBatch(CAMPAIGN_ID)).thenReturn(true);

        service.evaluateDue();

        verify(campaigns).scheduleAbEvaluation(eq(CAMPAIGN_ID), any(Instant.class));
        verify(messages, never()).countByCampaignAndVariant(anyLong());
        verify(campaigns, never()).claimAbWinner(anyLong(), anyString());
    }

    @Test
    void evaluateDue_tooSmallSample_isDeferred() {
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(dueWinnerFlowCampaign("OPEN")));
        stubDeliveries(9, 100);   // A 가 최소 표본(10) 미만
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "A")).thenReturn(1L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "B")).thenReturn(40L);

        service.evaluateDue();

        verify(campaigns).scheduleAbEvaluation(eq(CAMPAIGN_ID), any(Instant.class));
        verify(campaigns, never()).claimAbWinner(anyLong(), anyString());
    }

    @Test
    void evaluateDue_noEngagementAtAll_isDeferred() {
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(dueWinnerFlowCampaign("OPEN")));
        stubDeliveries(100, 100);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "A")).thenReturn(0L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "B")).thenReturn(0L);

        service.evaluateDue();

        verify(campaigns).scheduleAbEvaluation(eq(CAMPAIGN_ID), any(Instant.class));
        verify(campaigns, never()).claimAbWinner(anyLong(), anyString());
    }

    @Test
    void evaluateDue_pastTheDeferralDeadline_decidesOnWeakEvidence_soTheHoldoutIsNotHeldForever() {
        // 24시간 넘게 근거가 안 모이면 있는 근거로 확정한다 — 홀드아웃 95% 를 영영 붙들 수 없다
        when(campaigns.findDueForAbEvaluation(any(Instant.class)))
                .thenReturn(List.of(overdueWinnerFlowCampaign("OPEN")));
        stubDeliveries(5, 5);   // 표본 부족이지만 B 가 앞선다
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "A")).thenReturn(0L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "B")).thenReturn(2L);
        when(campaigns.claimAbWinner(CAMPAIGN_ID, "B")).thenReturn(true);
        when(messages.findPendingHeldIdsByCampaign(CAMPAIGN_ID)).thenReturn(List.of(500L));

        int decided = service.evaluateDue();

        assertThat(decided).isEqualTo(1);
        verify(campaigns, never()).scheduleAbEvaluation(anyLong(), any(Instant.class));
        verify(mailQueue).enqueue(500L);
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
