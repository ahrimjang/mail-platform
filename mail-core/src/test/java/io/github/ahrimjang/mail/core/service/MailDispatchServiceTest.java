package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository.MessageCounts;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailDispatchServiceTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final Long MESSAGE_ID = 42L;
    private static final Long CAMPAIGN_ID = 7L;
    private static final String RECIPIENT = "user@example.com";

    @Mock
    private MailMessageRepository messages;
    @Mock
    private CampaignRepository campaigns;
    @Mock
    private MailSender sender;
    @Mock
    private SuppressionRepository suppressions;
    @Mock
    private ContactRepository contacts;

    private MailDispatchService service;

    @BeforeEach
    void setUp() {
        // Real assembly collaborators keep the produced HTML realistic; only ports are mocked.
        service = new MailDispatchService(messages, campaigns, sender, suppressions,
                new TrackingRewriter(), new TemplateRenderer(), contacts, BASE_URL);
    }

    private MailMessage queuedMessage(Long contactId) {
        MailMessage message = MailMessage.queued(CAMPAIGN_ID, RECIPIENT, contactId);
        message.setId(MESSAGE_ID);
        return message;
    }

    private Campaign campaign(String subject, String body) {
        Campaign campaign = Campaign.draft(subject, body);
        campaign.setId(CAMPAIGN_ID);
        return campaign;
    }

    private static MessageCounts counts(long pending, long sending) {
        return new MessageCounts(pending + sending + 1, pending, sending, 1, 0, 0, 0);
    }

    @Test
    void dispatchOne_skipsWhenClaimLost() throws Exception {
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(false);

        service.dispatchOne(MESSAGE_ID);

        verify(messages, never()).findById(anyLong());
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void dispatchOne_marksFailedWhenCampaignMissing() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        service.dispatchOne(MESSAGE_ID);

        ArgumentCaptor<MailMessage> saved = ArgumentCaptor.forClass(MailMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MessageStatus.FAILED);
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void dispatchOne_marksSuppressedWithoutSending() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("subject", "body")));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(true);
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(1, 0));

        service.dispatchOne(MESSAGE_ID);

        ArgumentCaptor<MailMessage> saved = ArgumentCaptor.forClass(MailMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MessageStatus.SUPPRESSED);
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void dispatchOne_sendsAndMarksSentOnHappyPath() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("Hello", "<p>Body</p>")));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(false);
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(1, 0));

        service.dispatchOne(MESSAGE_ID);

        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageId = ArgumentCaptor.forClass(String.class);
        verify(sender).send(recipient.capture(), subject.capture(), body.capture(), messageId.capture(), any(), any());
        assertThat(recipient.getValue()).isEqualTo(RECIPIENT);
        assertThat(subject.getValue()).isEqualTo("Hello");
        assertThat(body.getValue()).contains("<p>Body</p>");
        assertThat(messageId.getValue()).isEqualTo(String.valueOf(MESSAGE_ID));

        ArgumentCaptor<MailMessage> saved = ArgumentCaptor.forClass(MailMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void dispatchOne_passesCampaignSenderIdentityToTheMailSender() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        Campaign withSender = campaign("Hello", "<p>Body</p>");
        withSender.setSenderName("Acme 팀");
        withSender.setSenderEmail("hello@acme.io");
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(withSender));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(false);
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(1, 0));

        service.dispatchOne(MESSAGE_ID);

        verify(sender).send(eq(RECIPIENT), anyString(), anyString(), anyString(),
                eq("Acme 팀"), eq("hello@acme.io"));
    }

    @Test
    void dispatchOne_personalizesFromContactVariables() throws Exception {
        Long contactId = 99L;
        MailMessage message = queuedMessage(contactId);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(
                Optional.of(campaign("Hi {{firstName}}", "<p>Dear {{firstName}}</p>")));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(false);
        when(contacts.findById(contactId)).thenReturn(
                Optional.of(Contact.of(RECIPIENT, "Ahrim", "Jang", Map.of())));
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(1, 0));

        service.dispatchOne(MESSAGE_ID);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(RECIPIENT), subject.capture(), body.capture(), anyString(), any(), any());
        assertThat(subject.getValue()).isEqualTo("Hi Ahrim");
        assertThat(body.getValue()).contains("<p>Dear Ahrim</p>");
    }

    @Test
    void dispatchOne_rendersEmailVariableForRawRecipient() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(
                Optional.of(campaign("Welcome", "<p>Sent to {{email}}</p>")));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(false);
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(1, 0));

        service.dispatchOne(MESSAGE_ID);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(RECIPIENT), eq("Welcome"), body.capture(), anyString(), any(), any());
        assertThat(body.getValue()).contains("<p>Sent to " + RECIPIENT + "</p>");
        // Raw recipients have no contact link, so the contact port must not be hit.
        verify(contacts, never()).findById(anyLong());
    }

    @Test
    void dispatchOne_assemblesTrackedHtmlWithUnsubscribeAndOpenPixel() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(
                Optional.of(campaign("Deals", "<a href=\"https://example.com/deal\">deal</a>")));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(false);
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(1, 0));

        service.dispatchOne(MESSAGE_ID);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(RECIPIENT), anyString(), body.capture(), anyString(), any(), any());
        String html = body.getValue();
        // Original link is rewritten through the click-tracking redirect.
        assertThat(html).doesNotContain("href=\"https://example.com/deal\"");
        assertThat(html).contains(BASE_URL + "/api/track/click/" + message.getTrackingToken() + "?u=");
        assertThat(html).contains(BASE_URL + "/api/unsubscribe/" + message.getUnsubToken());
        assertThat(html).contains(BASE_URL + "/api/track/open/" + message.getTrackingToken());
    }

    @Test
    void dispatchOne_marksBouncedAndSuppressesOnSendFailure() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("subject", "body")));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(false);
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(1, 0));
        doThrow(new MailSender.MailSendException("mailbox unavailable"))
                .when(sender).send(anyString(), anyString(), anyString(), anyString(), any(), any());

        service.dispatchOne(MESSAGE_ID);

        ArgumentCaptor<MailMessage> saved = ArgumentCaptor.forClass(MailMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MessageStatus.BOUNCED);
        assertThat(saved.getValue().getErrorMessage()).isEqualTo("mailbox unavailable");

        ArgumentCaptor<Suppression> suppression = ArgumentCaptor.forClass(Suppression.class);
        verify(suppressions).save(suppression.capture());
        assertThat(suppression.getValue().getEmail()).isEqualTo(RECIPIENT);
        assertThat(suppression.getValue().getReason()).isEqualTo("bounce");
    }

    @Test
    void dispatchOne_completesCampaignWhenQueueDrained() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("subject", "body")));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(false);
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(0, 0));

        service.dispatchOne(MESSAGE_ID);

        verify(campaigns).updateStatus(CAMPAIGN_ID, CampaignStatus.COMPLETED);
    }

    @Test
    void dispatchOne_doesNotCompleteWhileMessagesStillInFlight() throws Exception {
        MailMessage message = queuedMessage(null);
        when(messages.claim(eq(MESSAGE_ID), any(Duration.class))).thenReturn(true);
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("subject", "body")));
        when(suppressions.existsByEmail(RECIPIENT)).thenReturn(false);
        // pending drained but one message is claimed (SENDING) by a concurrent consumer
        when(messages.countByCampaign(CAMPAIGN_ID)).thenReturn(counts(0, 1));

        service.dispatchOne(MESSAGE_ID);

        verify(campaigns, never()).updateStatus(CAMPAIGN_ID, CampaignStatus.COMPLETED);
    }
}
