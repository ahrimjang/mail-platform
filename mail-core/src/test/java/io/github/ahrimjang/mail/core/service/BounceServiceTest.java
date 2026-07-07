package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.BounceNotification;
import io.github.ahrimjang.mail.common.BounceType;
import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.EmailEventPublisher;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BounceServiceTest {

    private static final Long MESSAGE_ID = 10L;
    private static final Long CAMPAIGN_ID = 3L;
    private static final String EMAIL = "bouncer@example.com";

    @Mock
    private SuppressionRepository suppressions;
    @Mock
    private MailMessageRepository messages;
    @Mock
    private EmailEventPublisher events;

    private BounceService service;

    @BeforeEach
    void setUp() {
        service = new BounceService(suppressions, messages, events);
    }

    private MailMessage sentMessage() {
        MailMessage message = MailMessage.queued(CAMPAIGN_ID, EMAIL);
        message.setId(MESSAGE_ID);
        message.markSent();
        return message;
    }

    @Test
    void handle_hardBounceWithMessageId_marksBouncedRecordsEventAndSuppresses() {
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(sentMessage()));

        service.handle(new BounceNotification(EMAIL, BounceType.HARD_BOUNCE, "mailbox full", MESSAGE_ID));

        ArgumentCaptor<MailMessage> saved = ArgumentCaptor.forClass(MailMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MessageStatus.BOUNCED);
        assertThat(saved.getValue().getErrorMessage()).isEqualTo("mailbox full");

        ArgumentCaptor<EmailEvent> event = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(event.getValue().getCampaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(event.getValue().getType()).isEqualTo(EventType.BOUNCE);
        assertThat(event.getValue().getUrl()).isNull();

        ArgumentCaptor<Suppression> suppression = ArgumentCaptor.forClass(Suppression.class);
        verify(suppressions).save(suppression.capture());
        assertThat(suppression.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(suppression.getValue().getReason()).isEqualTo("hard_bounce");
    }

    @Test
    void handle_isIdempotentWhenMessageAlreadyBounced() {
        MailMessage message = sentMessage();
        message.markBounced("earlier notification");
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

        service.handle(new BounceNotification(EMAIL, BounceType.HARD_BOUNCE, "mailbox full", MESSAGE_ID));

        // Already BOUNCED: no second write, no duplicate event — but suppression still applies.
        verify(messages, never()).save(any(MailMessage.class));
        verifyNoInteractions(events);
        verify(suppressions).save(any(Suppression.class));
    }

    @Test
    void handle_softBounce_leavesEverythingUntouched() {
        service.handle(new BounceNotification(EMAIL, BounceType.SOFT_BOUNCE, "greylisted", null));

        verifyNoInteractions(messages, events, suppressions);
    }

    @Test
    void handle_complaintWithoutMessageId_suppressesOnly() {
        service.handle(new BounceNotification(EMAIL, BounceType.COMPLAINT, "marked as spam", null));

        ArgumentCaptor<Suppression> suppression = ArgumentCaptor.forClass(Suppression.class);
        verify(suppressions).save(suppression.capture());
        assertThat(suppression.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(suppression.getValue().getReason()).isEqualTo("complaint");
        verifyNoInteractions(messages, events);
    }

    @Test
    void handle_unknownMessageId_stillSuppressesForHardBounce() {
        when(messages.findById(MESSAGE_ID)).thenReturn(Optional.empty());

        service.handle(new BounceNotification(EMAIL, BounceType.HARD_BOUNCE, "unknown user", MESSAGE_ID));

        verify(messages, never()).save(any(MailMessage.class));
        verifyNoInteractions(events);
        verify(suppressions).save(any(Suppression.class));
    }
}
