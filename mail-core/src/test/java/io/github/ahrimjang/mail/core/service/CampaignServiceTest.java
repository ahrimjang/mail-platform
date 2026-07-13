package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.CreateCampaignRequest;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.domain.ContactList;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import io.github.ahrimjang.mail.core.port.MailQueue;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    private static final long CAMPAIGN_ID = 42L;

    @Mock
    private CampaignRepository campaigns;
    @Mock
    private MailMessageRepository messages;
    @Mock
    private EmailEventRepository events;
    @Mock
    private MailQueue mailQueue;
    @Mock
    private TemplateRepository templates;
    @Mock
    private ContactRepository contacts;
    @Mock
    private ContactListRepository lists;

    @InjectMocks
    private CampaignService service;

    private void stubCampaignSaveAssigningId() {
        when(campaigns.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            c.setId(CAMPAIGN_ID);
            return c;
        });
    }

    /** saveAll echoes its argument with ids 100, 101, ... assigned, like the real adapter. */
    private void stubMessageSaveAllAssigningIds() {
        when(messages.saveAll(anyList())).thenAnswer(inv -> {
            List<MailMessage> queued = inv.getArgument(0);
            long id = 100;
            for (MailMessage m : queued) {
                m.setId(id++);
            }
            return queued;
        });
    }

    private void stubViewCounts(long total, long pending) {
        when(messages.countByCampaign(CAMPAIGN_ID))
                .thenReturn(new MessageCounts(total, pending, 0, 0, 0, 0, 0));
        when(events.countDistinctMessages(CAMPAIGN_ID, EventType.OPEN)).thenReturn(0L);
        when(events.countDistinctMessages(CAMPAIGN_ID, EventType.CLICK)).thenReturn(0L);
    }

    @SuppressWarnings("unchecked")
    private List<MailMessage> capturedSavedMessages() {
        ArgumentCaptor<List<MailMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messages).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void create_direct_savesQueuedCampaignAndOnePendingMessagePerRecipient() {
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(2, 2);

        CampaignView view = service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi there</p>", List.of("a@example.com", "b@example.com"), null, null, null, null, null));

        ArgumentCaptor<Campaign> campaignCaptor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(campaignCaptor.capture());
        assertThat(campaignCaptor.getValue().getStatus()).isEqualTo(CampaignStatus.QUEUED);
        assertThat(campaignCaptor.getValue().getSubject()).isEqualTo("Hello");
        assertThat(campaignCaptor.getValue().getBody()).isEqualTo("<p>Hi there</p>");

        List<MailMessage> queued = capturedSavedMessages();
        assertThat(queued).hasSize(2);
        assertThat(queued).extracting(MailMessage::getRecipient)
                .containsExactly("a@example.com", "b@example.com");
        assertThat(queued).allSatisfy(m -> {
            assertThat(m.getStatus()).isEqualTo(MessageStatus.PENDING);
            assertThat(m.getCampaignId()).isEqualTo(CAMPAIGN_ID);
            assertThat(m.getUnsubToken()).isNotBlank();
            assertThat(m.getTrackingToken()).isNotBlank();
            assertThat(m.getContactId()).isNull();
        });

        assertThat(view.id()).isEqualTo(CAMPAIGN_ID);
        assertThat(view.status()).isEqualTo(CampaignStatus.QUEUED);
        assertThat(view.total()).isEqualTo(2);
        assertThat(view.pending()).isEqualTo(2);
    }

    @Test
    void create_direct_enqueuesOneJobPerSavedMessageId() {
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(2, 2);

        service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com", "b@example.com"), null, null, null, null, null));

        verify(mailQueue).enqueue(100L);
        verify(mailQueue).enqueue(101L);
        verifyNoMoreInteractions(mailQueue);
    }

    @Test
    void create_withTemplateId_snapshotsTemplateSubjectAndBody() {
        Template template = Template.create("welcome", "Welcome {{firstName}}", "<p>Hi {{firstName}}</p>");
        when(templates.findById(7L)).thenReturn(Optional.of(template));
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(1, 1);

        // Direct subject/body must be ignored when templateId is present.
        service.create(new CreateCampaignRequest(
                "ignored subject", "ignored body", List.of("a@example.com"), 7L, null, null, null, null));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("Welcome {{firstName}}");
        assertThat(captor.getValue().getBody()).isEqualTo("<p>Hi {{firstName}}</p>");
    }

    @Test
    void create_withUnknownTemplateId_throwsNoSuchElement() {
        when(templates.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                null, null, List.of("a@example.com"), 99L, null, null, null, null)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");

        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void create_withListId_defersRecipientExpansionToAFanoutJob() {
        // List campaigns are O(1) at create time: no members are loaded and no
        // messages are saved — the worker expands them off a single fan-out job.
        when(contacts.countByListId(5L)).thenReturn(2L);
        stubCampaignSaveAssigningId();
        stubViewCounts(0, 0);

        service.create(new CreateCampaignRequest("Subject", "<p>Body</p>", null, null, 5L, null, null, null));

        verify(mailQueue).enqueueFanout(CAMPAIGN_ID);
        verifyNoMoreInteractions(mailQueue);
        verify(messages, never()).saveAll(anyList());
    }

    @Test
    void create_withEmptyList_throwsIllegalArgument() {
        stubCampaignSaveAssigningId();
        when(contacts.countByListId(5L)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", null, null, 5L, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5");

        verify(messages, never()).saveAll(anyList());
        verifyNoInteractions(mailQueue);
    }

    @Test
    void create_withBlankSubjectOrBodyAndNoTemplate_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "  ", "<p>Body</p>", List.of("a@example.com"), null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", null, List.of("a@example.com"), null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        // Validation happens before any persistence or queueing.
        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void recentMessages_mapsRowsAndCapsLimit() {
        Campaign existing = Campaign.draft("Hello", "<p>Hi</p>");
        existing.setId(CAMPAIGN_ID);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(existing));
        MailMessage m = MailMessage.queued(CAMPAIGN_ID, "a@example.com");
        m.setId(100L);
        m.markSent();
        when(messages.findRecentByCampaign(CAMPAIGN_ID, 200)).thenReturn(List.of(m));

        // limit above the cap is clamped to 200 before hitting the port
        var log = service.recentMessages(CAMPAIGN_ID, 5000);

        assertThat(log).hasSize(1);
        assertThat(log.get(0).id()).isEqualTo(100L);
        assertThat(log.get(0).recipient()).isEqualTo("a@example.com");
        assertThat(log.get(0).status()).isEqualTo(MessageStatus.SENT);
        assertThat(log.get(0).updatedAt()).isNotNull();
    }

    @Test
    void sendLog_mapsBucketsAndClampsParams() {
        Campaign existing = Campaign.draft("Hello", "<p>Hi</p>");
        existing.setId(CAMPAIGN_ID);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(existing));
        Instant bucket = Instant.parse("2026-07-07T12:00:00Z");
        when(messages.aggregateLogByCampaign(CAMPAIGN_ID, 3600, 200)).thenReturn(List.of(
                new MailMessageRepository.SendLogBucket(bucket, MessageStatus.SENT, 480, null),
                new MailMessageRepository.SendLogBucket(bucket, MessageStatus.BOUNCED, 3, "mailbox full")));

        // bucketSeconds above 3600 and limit above 200 are clamped before the port call
        var log = service.sendLog(CAMPAIGN_ID, 99999, 5000);

        assertThat(log).hasSize(2);
        assertThat(log.get(0).time()).isEqualTo(bucket);
        assertThat(log.get(0).status()).isEqualTo(MessageStatus.SENT);
        assertThat(log.get(0).count()).isEqualTo(480);
        assertThat(log.get(0).detail()).isNull();
        assertThat(log.get(1).count()).isEqualTo(3);
        assertThat(log.get(1).detail()).isEqualTo("mailbox full");
    }

    @Test
    void sendLog_unknownCampaign_throwsNoSuchElement() {
        when(campaigns.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendLog(999L, 10, 50))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("999");
        verifyNoInteractions(messages);
    }

    @Test
    void recentMessages_unknownCampaign_throwsNoSuchElement() {
        when(campaigns.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recentMessages(999L, 50))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("999");
        verifyNoInteractions(messages);
    }

    @Test
    void create_withSenderFields_storesThemOnTheCampaignAndView() {
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(1, 1);

        CampaignView view = service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com"), null, null,
                "Acme 팀", "hello@acme.io", null));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getSenderName()).isEqualTo("Acme 팀");
        assertThat(captor.getValue().getSenderEmail()).isEqualTo("hello@acme.io");
        assertThat(view.senderName()).isEqualTo("Acme 팀");
        assertThat(view.senderEmail()).isEqualTo("hello@acme.io");
    }

    @Test
    void create_withFutureScheduledAt_persistsMessagesButDefersEnqueue() {
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(2, 2);
        Instant later = Instant.now().plus(1, ChronoUnit.HOURS);

        CampaignView view = service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com", "b@example.com"), null, null,
                null, null, later));

        // Messages are persisted as PENDING for the scheduler to release later...
        assertThat(capturedSavedMessages()).hasSize(2);
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getScheduledAt()).isEqualTo(later);
        assertThat(captor.getValue().getEnqueuedAt()).isNull();
        assertThat(view.scheduledAt()).isEqualTo(later);
        // ...but nothing is published to the queue yet.
        verifyNoInteractions(mailQueue);
    }

    @Test
    void create_withPastScheduledAt_sendsImmediately() {
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(1, 1);

        service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com"), null, null,
                null, null, Instant.now().minus(1, ChronoUnit.MINUTES)));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getEnqueuedAt()).isNotNull();
        verify(mailQueue).enqueue(100L);
    }

    @Test
    void create_recordsTemplateAndListProvenanceAndResolvesNamesInView() {
        Template template = Template.create("welcome", "Welcome", "<p>Hi</p>");
        template.setId(7L);
        when(templates.findById(7L)).thenReturn(Optional.of(template));
        ContactList list = ContactList.of("뉴스레터 구독자", null);
        list.setId(5L);
        when(lists.findById(5L)).thenReturn(Optional.of(list));
        when(contacts.countByListId(5L)).thenReturn(1L);
        stubCampaignSaveAssigningId();
        stubViewCounts(0, 0);

        CampaignView view = service.create(new CreateCampaignRequest(
                null, null, null, 7L, 5L, null, null, null));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getTemplateId()).isEqualTo(7L);
        assertThat(captor.getValue().getListId()).isEqualTo(5L);
        assertThat(view.templateId()).isEqualTo(7L);
        assertThat(view.templateName()).isEqualTo("welcome");
        assertThat(view.listId()).isEqualTo(5L);
        assertThat(view.listName()).isEqualTo("뉴스레터 구독자");
    }

    @Test
    void view_ofDeletedTemplateSource_keepsIdButLeavesNameNull() {
        Campaign existing = Campaign.draft("Hello", "<p>Hi</p>");
        existing.setId(CAMPAIGN_ID);
        existing.setTemplateId(7L);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(existing));
        when(templates.findById(7L)).thenReturn(Optional.empty());
        stubViewCounts(1, 0);

        CampaignView view = service.get(CAMPAIGN_ID);

        assertThat(view.templateId()).isEqualTo(7L);
        assertThat(view.templateName()).isNull();
    }

    @Test
    void cancelSchedule_winningClaim_cancelsPendingMessagesAndReturnsCanceledView() {
        Campaign scheduled = Campaign.draft("Hello", "<p>Hi</p>");
        scheduled.setId(CAMPAIGN_ID);
        scheduled.setStatus(CampaignStatus.QUEUED);
        when(campaigns.findById(CAMPAIGN_ID)).thenAnswer(inv -> {
            // after the claim the reload sees the canceled row
            scheduled.setStatus(CampaignStatus.CANCELED);
            return Optional.of(scheduled);
        });
        when(campaigns.claimForCancel(CAMPAIGN_ID)).thenReturn(true);
        stubViewCounts(3, 0);

        CampaignView view = service.cancelSchedule(CAMPAIGN_ID);

        verify(messages).cancelPendingByCampaign(CAMPAIGN_ID);
        assertThat(view.status()).isEqualTo(CampaignStatus.CANCELED);
        verifyNoInteractions(mailQueue);
    }

    @Test
    void cancelSchedule_lostClaim_throwsIllegalStateWithoutTouchingMessages() {
        Campaign released = Campaign.draft("Hello", "<p>Hi</p>");
        released.setId(CAMPAIGN_ID);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(released));
        // the scheduler's claimForEnqueue got there first
        when(campaigns.claimForCancel(CAMPAIGN_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.cancelSchedule(CAMPAIGN_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(CAMPAIGN_ID));

        verify(messages, never()).cancelPendingByCampaign(CAMPAIGN_ID);
    }

    @Test
    void cancelSchedule_unknownCampaign_throwsNoSuchElement() {
        when(campaigns.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelSchedule(999L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("999");
        verifyNoInteractions(messages);
    }

    @Test
    void content_returnsTheSnapshottedSubjectAndBody() {
        Campaign existing = Campaign.draft("Hello {{name}}", "<p>Hi {{name}}</p>");
        existing.setId(CAMPAIGN_ID);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(existing));

        var content = service.content(CAMPAIGN_ID);

        assertThat(content.subject()).isEqualTo("Hello {{name}}");
        assertThat(content.htmlBody()).isEqualTo("<p>Hi {{name}}</p>");
        // content is a plain read of the snapshot — no counting queries involved
        verifyNoInteractions(messages, events);
    }

    @Test
    void content_unknownCampaign_throwsNoSuchElement() {
        when(campaigns.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.content(999L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("999");
    }

    @Test
    void create_withEmptyRecipientsAndNoListId_throwsIllegalArgument() {
        stubCampaignSaveAssigningId();

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", List.of(), null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(messages, never()).saveAll(anyList());
        verifyNoInteractions(mailQueue);
    }
}
