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
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import io.github.ahrimjang.mail.core.port.MailQueue;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    /** The acting tenant every scoped call resolves to in these tests. */
    private static final long WS = 7L;

    @Mock
    private WorkspaceContext ctx;

    @BeforeEach
    void stubWorkspaceContext() {
        org.mockito.Mockito.lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
    }

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
                "Hello", "<p>Hi there</p>", List.of("a@example.com", "b@example.com"), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));

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
                "Hello", "<p>Hi</p>", List.of("a@example.com", "b@example.com"), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));

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
                "ignored subject", "ignored body", List.of("a@example.com"), 7L, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("Welcome {{firstName}}");
        assertThat(captor.getValue().getBody()).isEqualTo("<p>Hi {{firstName}}</p>");
    }

    @Test
    void create_withUnknownTemplateId_throwsNoSuchElement() {
        when(templates.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                null, null, List.of("a@example.com"), 99L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");

        verifyNoInteractions(campaigns, messages, mailQueue);
    }


    /** The campaign's target list, owned by the acting tenant. */
    private void stubOwnedList(long listId) {
        ContactList list = ContactList.of("타깃", null);
        list.setId(listId);
        list.setWorkspaceId(WS);
        org.mockito.Mockito.lenient().when(lists.findById(listId)).thenReturn(Optional.of(list));
    }

    @Test
    void create_withListId_defersRecipientExpansionToAFanoutJob() {
        // List campaigns are O(1) at create time: no members are loaded and no
        // messages are saved — the worker expands them off a single fan-out job.
        stubOwnedList(5L);
        when(contacts.countByListId(5L)).thenReturn(2L);
        stubCampaignSaveAssigningId();
        stubViewCounts(0, 0);

        service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", null, null, 5L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        verify(mailQueue).enqueueFanout(CAMPAIGN_ID);
        verifyNoMoreInteractions(mailQueue);
        verify(messages, never()).saveAll(anyList());
    }

    @Test
    void create_withEmptyList_throwsIllegalArgument() {
        stubOwnedList(5L);
        stubCampaignSaveAssigningId();
        when(contacts.countByListId(5L)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", null, null, 5L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5");

        verify(messages, never()).saveAll(anyList());
        verifyNoInteractions(mailQueue);
    }

    @Test
    void create_withBlankSubjectOrBodyAndNoTemplate_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "  ", "<p>Body</p>", List.of("a@example.com"), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", null, List.of("a@example.com"), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        // Validation happens before any persistence or queueing.
        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void recentMessages_mapsRowsAndCapsLimit() {
        Campaign existing = Campaign.draft("Hello", "<p>Hi</p>");
        existing.setWorkspaceId(WS);
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
        existing.setWorkspaceId(WS);
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
                "Acme 팀", "hello@acme.io", null, null, null, null, null, null, null, null, null, null, null, null, null));

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
                null, null, later, null, null, null, null, null, null, null, null, null, null, null, null));

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
                null, null, Instant.now().minus(1, ChronoUnit.MINUTES), null, null, null, null, null, null, null, null, null, null, null, null));

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
        list.setWorkspaceId(WS);
        list.setId(5L);
        when(lists.findById(5L)).thenReturn(Optional.of(list));
        when(contacts.countByListId(5L)).thenReturn(1L);
        stubCampaignSaveAssigningId();
        stubViewCounts(0, 0);

        CampaignView view = service.create(new CreateCampaignRequest(
                null, null, null, 7L, 5L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

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
        existing.setWorkspaceId(WS);
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
        scheduled.setWorkspaceId(WS);
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
        released.setWorkspaceId(WS);
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
        existing.setWorkspaceId(WS);
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
    void create_abTest_storesVariantFieldsAndAssignsVariantsOnAdHocMessages() {
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(2, 2);

        // Pick one recipient per bucket side so both variants provably appear;
        // computing the expectation via the assigner keeps the test hash-proof.
        String recipientA = firstEmailWithVariant("A", 50);
        String recipientB = firstEmailWithVariant("B", 50);

        service.create(new CreateCampaignRequest(
                "Hello", "<p>A body</p>", List.of(recipientA, recipientB), null, null, null, null, null,
                "Hello B", "<p>B body</p>", null, null, null, null, null, null, null, null, null, null));

        ArgumentCaptor<Campaign> campaignCaptor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(campaignCaptor.capture());
        Campaign saved = campaignCaptor.getValue();
        assertThat(saved.isAbTest()).isTrue();
        assertThat(saved.getAbSubjectB()).isEqualTo("Hello B");
        assertThat(saved.getAbBodyB()).isEqualTo("<p>B body</p>");
        assertThat(saved.getAbSplitPercent()).isEqualTo(50); // null split defaults to 50

        List<MailMessage> queued = capturedSavedMessages();
        assertThat(queued).extracting(MailMessage::getVariant).containsExactly("A", "B");
    }

    @Test
    void create_abTest_withSplitPercentOutOfRange_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com"), null, null, null, null, null,
                "Hello B", null, null, 0, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abSplitPercent");

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com"), null, null, null, null, null,
                "Hello B", null, null, 100, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abSplitPercent");

        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void create_abTest_withAbTemplateId_snapshotsTemplateAsVariantB() {
        Template abTemplate = Template.create("promo-b", "B from template", "<p>B body from template</p>");
        when(templates.findById(8L)).thenReturn(Optional.of(abTemplate));
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(1, 1);

        // Direct B fields must be ignored when abTemplateId is present — the
        // A/B mirror of how templateId overrides the main subject/body.
        service.create(new CreateCampaignRequest(
                "Hello", "<p>A body</p>", List.of("a@example.com"), null, null, null, null, null,
                "ignored B subject", "ignored B body", 8L, null, null, null, null, null, null, null, null, null));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getAbSubjectB()).isEqualTo("B from template");
        assertThat(captor.getValue().getAbBodyB()).isEqualTo("<p>B body from template</p>");
        assertThat(captor.getValue().getAbSplitPercent()).isEqualTo(50);
    }

    @Test
    void view_ofAbCampaign_carriesPerVariantDeliveryAndEngagementStats() {
        Campaign existing = Campaign.draft("Hello", "<p>Hi</p>");
        existing.setWorkspaceId(WS);
        existing.setId(CAMPAIGN_ID);
        existing.setAbSubjectB("Hello B");
        existing.setAbSplitPercent(50);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(existing));
        stubViewCounts(10, 0);
        when(messages.countByCampaignAndVariant(CAMPAIGN_ID)).thenReturn(List.of(
                new MailMessageRepository.VariantDelivery("A", 6, 5),
                new MailMessageRepository.VariantDelivery("B", 4, 4)));
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "A")).thenReturn(3L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.CLICK, "A")).thenReturn(1L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.OPEN, "B")).thenReturn(2L);
        when(events.countDistinctMessagesByVariant(CAMPAIGN_ID, EventType.CLICK, "B")).thenReturn(2L);

        CampaignView view = service.get(CAMPAIGN_ID);

        assertThat(view.variants()).containsExactly(
                new CampaignView.VariantStats("A", 6, 5, 3, 1),
                new CampaignView.VariantStats("B", 4, 4, 2, 2));
    }

    @Test
    void view_ofPlainCampaign_hasNullVariants() {
        Campaign existing = Campaign.draft("Hello", "<p>Hi</p>");
        existing.setWorkspaceId(WS);
        existing.setId(CAMPAIGN_ID);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(existing));
        stubViewCounts(1, 0);

        CampaignView view = service.get(CAMPAIGN_ID);

        assertThat(view.variants()).isNull();
        verify(messages, never()).countByCampaignAndVariant(CAMPAIGN_ID);
    }

    @Test
    void create_winnerFlow_enqueuesOnlyTheTestBatchAndSchedulesEvaluation() {
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(2, 2);

        // One recipient in the test group and one in the holdout, hash-proof
        // via the assigner itself — held rows must be saved but never enqueued.
        String tested = firstEmailWithHoldout(false, 20, 50);
        String held = firstEmailWithHoldout(true, 20, 50);
        Instant before = Instant.now();

        service.create(new CreateCampaignRequest(
                "Hello", "<p>A body</p>", List.of(tested, held), null, null, null, null, null,
                "Hello B", "<p>B body</p>", null, null, 20, "OPEN", 30, null, null, null, null, null));

        ArgumentCaptor<Campaign> campaignCaptor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(campaignCaptor.capture());
        Campaign saved = campaignCaptor.getValue();
        assertThat(saved.hasWinnerFlow()).isTrue();
        assertThat(saved.getAbTestPercent()).isEqualTo(20);
        assertThat(saved.getAbEvalMetric()).isEqualTo("OPEN");
        assertThat(saved.getAbEvalWaitMinutes()).isEqualTo(30);

        List<MailMessage> queued = capturedSavedMessages();
        assertThat(queued).hasSize(2);
        assertThat(queued.get(0).getVariant()).isNotNull();
        assertThat(queued.get(1).getVariant()).isNull();
        // Only the test row (id 100) is published; the held row (id 101) waits.
        verify(mailQueue).enqueue(100L);
        verify(mailQueue, never()).enqueue(101L);

        ArgumentCaptor<Instant> evaluateAt = ArgumentCaptor.forClass(Instant.class);
        verify(campaigns).scheduleAbEvaluation(eq(CAMPAIGN_ID), evaluateAt.capture());
        assertThat(evaluateAt.getValue()).isAfter(before);
    }

    @Test
    void create_winnerFlow_defaultsMetricToOpenAndWaitToSixtyMinutes() {
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(1, 1);

        service.create(new CreateCampaignRequest(
                "Hello", "<p>A body</p>", List.of("a@example.com"), null, null, null, null, null,
                "Hello B", null, null, null, 20, null, null, null, null, null, null, null));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getAbEvalMetric()).isEqualTo("OPEN");
        assertThat(captor.getValue().getAbEvalWaitMinutes()).isEqualTo(60);
    }

    @Test
    void create_withAbTestPercentButNoBContent_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com"), null, null, null, null, null,
                null, null, null, null, 20, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A/B content");

        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void create_withAbTestPercentOutOfRange_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com"), null, null, null, null, null,
                "Hello B", null, null, null, 4, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abTestPercent");

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com"), null, null, null, null, null,
                "Hello B", null, null, null, 91, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abTestPercent");

        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void create_withBadEvalMetric_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Hello", "<p>Hi</p>", List.of("a@example.com"), null, null, null, null, null,
                "Hello B", null, null, null, 20, "BOUNCE", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abEvalMetric");

        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    /** First generated address the assigner puts on {@code variant} at the given split. */
    private static String firstEmailWithVariant(String variant, int splitPercent) {
        for (int i = 0; i < 1000; i++) {
            String email = "user" + i + "@example.com";
            if (variant.equals(AbVariantAssigner.assign(email, splitPercent))) {
                return email;
            }
        }
        throw new IllegalStateException("no email found for variant " + variant);
    }

    /** First generated address the holdout assigner holds back (or not) at the given shares. */
    private static String firstEmailWithHoldout(boolean held, int testPercent, int splitPercent) {
        for (int i = 0; i < 1000; i++) {
            String email = "user" + i + "@example.com";
            if (held == (AbVariantAssigner.assignWithHoldout(email, testPercent, splitPercent) == null)) {
                return email;
            }
        }
        throw new IllegalStateException("no email found with held=" + held);
    }

    @Test
    void create_withEmptyRecipientsAndNoListId_throwsIllegalArgument() {
        stubCampaignSaveAssigningId();

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(messages, never()).saveAll(anyList());
        verifyNoInteractions(mailQueue);
    }

    @Test
    void saveDraft_persistsDraftWithoutQueueingAnything() {
        stubCampaignSaveAssigningId();
        stubViewCounts(0, 0);

        CampaignView view = service.saveDraft(new CreateCampaignRequest(
                "초안 제목", "<p>쓰다 만 본문</p>", List.of("a@example.com", "b@example.com"),
                null, null, null, null, null, null, null, null, null, null, null, null,
                "여름 세일 준비", null, null, null, null));

        assertThat(view.status()).isEqualTo(CampaignStatus.DRAFT);
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getDraftRecipients()).isEqualTo("a@example.com\nb@example.com");
        verifyNoInteractions(mailQueue);
        verify(messages, never()).saveAll(anyList());
    }

    @Test
    void saveDraft_rejectsACompletelyEmptyForm() {
        assertThatThrownBy(() -> service.saveDraft(new CreateCampaignRequest(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(campaigns, never()).save(any());
    }

    @Test
    void deleteDraft_refusesToDeleteALaunchedCampaign() {
        Campaign launched = Campaign.draft("s", "b");
        launched.setWorkspaceId(WS);
        launched.setId(CAMPAIGN_ID);
        launched.setStatus(CampaignStatus.QUEUED);
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(launched));

        assertThatThrownBy(() -> service.deleteDraft(CAMPAIGN_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(campaigns, never()).deleteById(anyLong());
    }
}
