package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Suppression;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuppressionServiceTest {

    @Mock
    private MailMessageRepository messages;

    @Mock
    private SuppressionRepository suppressions;

    private SuppressionService service;

    @BeforeEach
    void setUp() {
        service = new SuppressionService(messages, suppressions);
    }

    @Test
    void suppressByUnsubToken_knownTokenSuppressesRecipientWithUnsubscribeReason() {
        MailMessage message = MailMessage.queued(1L, "leaver@x.com");
        when(messages.findByUnsubToken("tok")).thenReturn(Optional.of(message));

        service.suppressByUnsubToken("tok");

        ArgumentCaptor<Suppression> captor = ArgumentCaptor.forClass(Suppression.class);
        verify(suppressions).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("leaver@x.com");
        assertThat(captor.getValue().getReason()).isEqualTo("unsubscribe");
    }

    @Test
    void suppressByUnsubToken_unknownTokenSavesNothing() {
        when(messages.findByUnsubToken("nope")).thenReturn(Optional.empty());

        service.suppressByUnsubToken("nope");

        verify(suppressions, never()).save(any());
    }

    @Test
    void isSuppressed_delegatesToRepository() {
        when(suppressions.existsByEmail("gone@x.com")).thenReturn(true);
        when(suppressions.existsByEmail("here@x.com")).thenReturn(false);

        assertThat(service.isSuppressed("gone@x.com")).isTrue();
        assertThat(service.isSuppressed("here@x.com")).isFalse();
    }
}
