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
        when(messages.countSentByContact()).thenReturn(List.of(
                new MailMessageRepository.ContactSentCount(1L, 2),
                new MailMessageRepository.ContactSentCount(2L, 2)));
        when(events.countEngagementByContact()).thenReturn(List.of(
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
        verify(messages, never()).countSentByContact();
    }
}
