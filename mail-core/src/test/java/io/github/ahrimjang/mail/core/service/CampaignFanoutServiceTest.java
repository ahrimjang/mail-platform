package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignFanoutServiceTest {

    private static final long CAMPAIGN_ID = 42L;
    private static final long LIST_ID = 5L;
    private static final int PAGE = 1000;

    @Mock
    private CampaignRepository campaigns;
    @Mock
    private MailMessageRepository messages;
    @Mock
    private ContactRepository contacts;
    @Mock
    private EmailEventRepository events;
    @Mock
    private MailQueue mailQueue;

    @Mock
    private NotificationService notifications;   // mock 기본 no-op
    @Mock
    private io.github.ahrimjang.mail.core.port.SuppressionRepository suppressions;   // 기본: 빈 목록 = 억제 없음

    @InjectMocks
    private CampaignFanoutService service;

    private static Campaign listCampaign() {
        Campaign c = Campaign.draft("Subject", "<p>Body</p>");
        c.setId(CAMPAIGN_ID);
        c.setListId(LIST_ID);
        return c;
    }

    private static List<Contact> contactPage(long startId, int size) {
        List<Contact> page = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long id = startId + i;
            Contact contact = Contact.of("c" + id + "@example.com", null, null, null);
            contact.setId(id);
            page.add(contact);
        }
        return page;
    }

    /** saveAll echoes its argument with sequential ids assigned, like the real adapter. */
    private void stubSaveAllAssigningIds() {
        when(messages.saveAll(anyList())).thenAnswer(inv -> {
            List<MailMessage> batch = inv.getArgument(0);
            long id = 1000;
            for (MailMessage m : batch) {
                m.setId(id++);
            }
            return batch;
        });
    }

    @Test
    void expand_stopsBetweenPages_whenTheCampaignWasAborted() {
        // 100만 명을 펼치는 도중 중단이 들어오면 다음 페이지로 넘어가지 않는다(ARCH-8)
        Campaign live = listCampaign();
        Campaign aborted = listCampaign();
        aborted.setStatus(io.github.ahrimjang.mail.common.CampaignStatus.CANCELED);
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        // 초기 로드 → 1페이지 전 확인 → 2페이지 전 확인(취소됨)
        when(campaigns.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(live), Optional.of(live), Optional.of(aborted));
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE))).thenReturn(contactPage(1L, PAGE));
        stubSaveAllAssigningIds();

        service.expand(CAMPAIGN_ID);

        verify(messages, times(1)).saveAll(anyList());                                   // 1페이지만
        verify(contacts, never()).findSubscribedByListIdAfter(eq(LIST_ID), eq((long) PAGE), eq(PAGE));
        verify(campaigns, never()).markExpanded(CAMPAIGN_ID);                             // SENDING 으로 안 넘어감
    }

    @Test
    void expand_recordsSuppressedRecipientsAsSuppressed_withoutEnqueuingThem() {
        // 억제 주소를 큐에 넣었다가 dispatch 에서 빼면 잡·토큰·DB 왕복이 낭비다(ARCH-10).
        // 행은 남겨 "발송 제외" 통계는 유지하되 큐에는 넣지 않는다.
        Campaign campaign = listCampaign();
        campaign.setWorkspaceId(7L);
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        List<Contact> page = contactPage(1L, 5);
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE))).thenReturn(page);
        when(suppressions.findSuppressedEmails(eq(7L), anyList()))
                .thenReturn(List.of("c2@example.com", "c4@example.com"));
        stubSaveAllAssigningIds();

        service.expand(CAMPAIGN_ID);

        ArgumentCaptor<List<MailMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(messages).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(5);
        assertThat(saved.getValue().stream()
                .filter(m -> m.getStatus() == io.github.ahrimjang.mail.common.MessageStatus.SUPPRESSED)
                .map(MailMessage::getRecipient))
                .containsExactlyInAnyOrder("c2@example.com", "c4@example.com");
        verify(mailQueue, times(3)).enqueue(anyLong());   // 억제 2건은 큐에 안 들어간다
    }

    @Test
    void expand_resumesAfterTheLastMaterialisedContact_whenRecoveredBySweeper() {
        // 팬아웃 도중 죽었다 되돌려진 캠페인: 이미 만든 메시지(contactId ≤ 1000) 뒤부터 잇는다 —
        // 0 부터 다시 돌면 같은 수신자에게 두 번 간다
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(listCampaign()));
        when(messages.maxContactIdByCampaign(CAMPAIGN_ID)).thenReturn(1000L);
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(1000L), eq(PAGE)))
                .thenReturn(contactPage(1001L, 300));
        stubSaveAllAssigningIds();

        service.expand(CAMPAIGN_ID);

        verify(contacts, never()).findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE));
        ArgumentCaptor<List<MailMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(messages).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(300);
        verify(mailQueue, times(300)).enqueue(anyLong());
        verify(campaigns).markExpanded(CAMPAIGN_ID);
    }

    @Test
    void expand_happyPath_savesEachBatchAndEnqueuesEverySavedIdThenMarksExpanded() {
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(listCampaign()));
        // A full page (id 1..1000) followed by a partial page (id 1001..1500), then empty.
        List<Contact> full = contactPage(1L, PAGE);
        List<Contact> partial = contactPage(PAGE + 1L, 500);
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE))).thenReturn(full);
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq((long) PAGE), eq(PAGE))).thenReturn(partial);
        stubSaveAllAssigningIds();
        when(messages.hasPendingOrSending(CAMPAIGN_ID)).thenReturn(true);

        service.expand(CAMPAIGN_ID);

        // One saveAll per batch (full + partial).
        verify(messages, times(2)).saveAll(anyList());
        // One enqueue per saved message id (1000 + 500).
        verify(mailQueue, times(1500)).enqueue(anyLong());
        verify(campaigns).markExpanded(CAMPAIGN_ID);
        // Still PENDING, so no early completion.
        verify(campaigns, never()).completeIfSending(CAMPAIGN_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void expand_abCampaign_assignsAVariantToEveryExpandedMessage() {
        Campaign ab = listCampaign();
        ab.setAbSubjectB("B subject");
        ab.setAbSplitPercent(50);
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(ab));
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE))).thenReturn(contactPage(1L, 100));
        stubSaveAllAssigningIds();
        when(messages.hasPendingOrSending(CAMPAIGN_ID)).thenReturn(true);

        service.expand(CAMPAIGN_ID);

        ArgumentCaptor<List<MailMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messages).saveAll(captor.capture());
        // Every message carries the deterministic assignment for its recipient.
        assertThat(captor.getValue()).isNotEmpty().allSatisfy(m ->
                assertThat(m.getVariant()).isEqualTo(AbVariantAssigner.assign(m.getRecipient(), 50)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expand_winnerFlowCampaign_savesHeldRowsWithoutEnqueuingAndStampsEvaluation() {
        Campaign winnerFlow = listCampaign();
        winnerFlow.setAbSubjectB("B subject");
        winnerFlow.setAbSplitPercent(50);
        winnerFlow.setAbTestPercent(20);
        winnerFlow.setAbEvalWaitMinutes(30);
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(winnerFlow));
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE))).thenReturn(contactPage(1L, 200));
        stubSaveAllAssigningIds();
        when(messages.hasPendingOrSending(CAMPAIGN_ID)).thenReturn(true);

        service.expand(CAMPAIGN_ID);

        ArgumentCaptor<List<MailMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messages).saveAll(captor.capture());
        List<MailMessage> saved = captor.getValue();
        // The holdout (variant null) is persisted but never published; only the
        // test batch is enqueued.
        long testRows = saved.stream().filter(m -> m.getVariant() != null).count();
        assertThat(testRows).isPositive().isLessThan(saved.size());
        verify(mailQueue, times((int) testRows)).enqueue(anyLong());
        verify(campaigns).markExpanded(CAMPAIGN_ID);
        verify(campaigns).scheduleAbEvaluation(eq(CAMPAIGN_ID), any(java.time.Instant.class));
    }

    @Test
    void expand_lostClaim_returnsWithoutTouchingContactsMessagesOrQueue() {
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(false);

        service.expand(CAMPAIGN_ID);

        verifyNoInteractions(contacts, messages, mailQueue);
        verify(campaigns, never()).markExpanded(anyLong());
    }

    @Test
    void expand_emptyList_marksExpandedThenCompletesImmediately() {
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(listCampaign()));
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE))).thenReturn(List.of());
        when(messages.hasPendingOrSending(CAMPAIGN_ID)).thenReturn(false);

        service.expand(CAMPAIGN_ID);

        verify(messages, never()).saveAll(anyList());
        verifyNoInteractions(mailQueue);
        verify(campaigns).markExpanded(CAMPAIGN_ID);
        verify(campaigns).completeIfSending(CAMPAIGN_ID);
    }

    @Test
    void expand_engagementSegment_skipsMembersBelowTheFloorsAndNeverDeliveredOnes() {
        Campaign seg = listCampaign();
        seg.setSegMinOpenPercent(50);
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(seg));
        // Contact 1: 2/2 opens (100%), contact 2: 0 opens, contact 3: never delivered.
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE)))
                .thenReturn(contactPage(1L, 3));
        when(messages.countSentByContact(any(), any())).thenReturn(List.of(
                new MailMessageRepository.ContactSentCount(1L, 2),
                new MailMessageRepository.ContactSentCount(2L, 2)));
        when(events.countEngagementByContact(any(), any())).thenReturn(List.of(
                new EmailEventRepository.ContactEngagement(1L, 2, 0)));
        stubSaveAllAssigningIds();
        when(messages.hasPendingOrSending(CAMPAIGN_ID)).thenReturn(true);

        service.expand(CAMPAIGN_ID);

        ArgumentCaptor<List<MailMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messages).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(MailMessage::getContactId).containsExactly(1L);
        verify(mailQueue, times(1)).enqueue(anyLong());
    }

    @Test
    void expand_withoutSegment_neverLoadsEngagementAggregates() {
        when(campaigns.claimForFanout(CAMPAIGN_ID)).thenReturn(true);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(listCampaign()));
        when(contacts.findSubscribedByListIdAfter(eq(LIST_ID), eq(0L), eq(PAGE)))
                .thenReturn(contactPage(1L, 2));
        stubSaveAllAssigningIds();
        when(messages.hasPendingOrSending(CAMPAIGN_ID)).thenReturn(true);

        service.expand(CAMPAIGN_ID);

        verifyNoInteractions(events);
        verify(messages, never()).countSentByContact(any(), any());
    }
}
