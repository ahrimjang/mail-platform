package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.CampaignView;
import io.github.ahrimjang.mail.common.CreateCampaignRequest;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
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
                "Hello", "<p>Hi there</p>", List.of("a@example.com", "b@example.com"), null, null));

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
                "Hello", "<p>Hi</p>", List.of("a@example.com", "b@example.com"), null, null));

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
                "ignored subject", "ignored body", List.of("a@example.com"), 7L, null));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("Welcome {{firstName}}");
        assertThat(captor.getValue().getBody()).isEqualTo("<p>Hi {{firstName}}</p>");
    }

    @Test
    void create_withUnknownTemplateId_throwsNoSuchElement() {
        when(templates.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                null, null, List.of("a@example.com"), 99L, null)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");

        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void create_withListId_fansOutOneMessagePerMemberCarryingContactId() {
        Contact alice = Contact.of("alice@example.com", "Alice", null, null);
        alice.setId(11L);
        Contact bob = Contact.of("bob@example.com", "Bob", null, null);
        bob.setId(12L);
        when(contacts.findByListId(5L)).thenReturn(List.of(alice, bob));
        stubCampaignSaveAssigningId();
        stubMessageSaveAllAssigningIds();
        stubViewCounts(2, 2);

        service.create(new CreateCampaignRequest("Subject", "<p>Body</p>", null, null, 5L));

        List<MailMessage> queued = capturedSavedMessages();
        assertThat(queued).hasSize(2);
        assertThat(queued).extracting(MailMessage::getRecipient)
                .containsExactly("alice@example.com", "bob@example.com");
        assertThat(queued).extracting(MailMessage::getContactId)
                .containsExactly(11L, 12L);
        verify(mailQueue).enqueue(100L);
        verify(mailQueue).enqueue(101L);
        verifyNoMoreInteractions(mailQueue);
    }

    @Test
    void create_withEmptyList_throwsIllegalArgument() {
        stubCampaignSaveAssigningId();
        when(contacts.findByListId(5L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", null, null, 5L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5");

        verify(messages, never()).saveAll(anyList());
        verifyNoInteractions(mailQueue);
    }

    @Test
    void create_withBlankSubjectOrBodyAndNoTemplate_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "  ", "<p>Body</p>", List.of("a@example.com"), null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", null, List.of("a@example.com"), null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        // Validation happens before any persistence or queueing.
        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void create_withEmptyRecipientsAndNoListId_throwsIllegalArgument() {
        stubCampaignSaveAssigningId();

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", List.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.create(new CreateCampaignRequest(
                "Subject", "<p>Body</p>", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(messages, never()).saveAll(anyList());
        verifyNoInteractions(mailQueue);
    }
}
