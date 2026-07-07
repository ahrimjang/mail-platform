package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.EmailEventPublisher;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

    @Mock
    private MailMessageRepository messages;

    @Mock
    private EmailEventPublisher events;

    private TrackingService service;

    @BeforeEach
    void setUp() {
        service = new TrackingService(messages, events);
    }

    private MailMessage messageWithIds(Long messageId, Long campaignId) {
        MailMessage m = MailMessage.queued(campaignId, "to@x.com");
        m.setId(messageId);
        return m;
    }

    @Test
    void recordOpen_savesOpenEventForKnownToken() {
        when(messages.findByTrackingToken("tok")).thenReturn(Optional.of(messageWithIds(11L, 5L)));

        service.recordOpen("tok");

        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publish(captor.capture());
        EmailEvent saved = captor.getValue();
        assertThat(saved.getMessageId()).isEqualTo(11L);
        assertThat(saved.getCampaignId()).isEqualTo(5L);
        assertThat(saved.getType()).isEqualTo(EventType.OPEN);
        assertThat(saved.getUrl()).isNull();
    }

    @Test
    void recordClick_savesClickEventWithUrlForKnownToken() {
        when(messages.findByTrackingToken("tok")).thenReturn(Optional.of(messageWithIds(11L, 5L)));

        service.recordClick("tok", "https://example.com/promo");

        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publish(captor.capture());
        EmailEvent saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(EventType.CLICK);
        assertThat(saved.getUrl()).isEqualTo("https://example.com/promo");
        assertThat(saved.getMessageId()).isEqualTo(11L);
        assertThat(saved.getCampaignId()).isEqualTo(5L);
    }

    @Test
    void recordOpen_unknownTokenSavesNothingAndDoesNotThrow() {
        when(messages.findByTrackingToken("nope")).thenReturn(Optional.empty());

        service.recordOpen("nope");

        verify(events, never()).publish(any());
    }

    @Test
    void recordClick_unknownTokenSavesNothingAndDoesNotThrow() {
        when(messages.findByTrackingToken("nope")).thenReturn(Optional.empty());

        service.recordClick("nope", "https://example.com");

        verify(events, never()).publish(any());
    }
}
