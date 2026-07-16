package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.common.TransactionalRequest;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionalServiceTest {

    /** The acting tenant every scoped call resolves to in these tests. */
    private static final long WS = 7L;

    @Mock
    private WorkspaceContext ctx;

    @BeforeEach
    void stubWorkspaceContext() {
        org.mockito.Mockito.lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
    }

    private static final long CAMPAIGN_ID = 42L;
    private static final long MESSAGE_ID = 100L;

    @Mock
    private TemplateRepository templates;
    @Mock
    private CampaignRepository campaigns;
    @Mock
    private MailMessageRepository messages;
    @Mock
    private MailQueue mailQueue;

    private TransactionalService service;

    @BeforeEach
    void setUp() {
        // Real renderer on purpose: the contract under test includes actual {{var}} substitution.
        service = new TransactionalService(templates, campaigns, messages, mailQueue, new TemplateRenderer(), ctx);
    }

    private void stubPersistenceAssigningIds() {
        when(campaigns.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            c.setId(CAMPAIGN_ID);
            return c;
        });
        when(messages.saveAll(anyList())).thenAnswer(inv -> {
            List<MailMessage> queued = inv.getArgument(0);
            queued.get(0).setId(MESSAGE_ID);
            return queued;
        });
    }

    @Test
    void send_rendersTemplateWithRequestVariablesBeforeSavingCampaign() {
        Template template = Template.create("welcome", "Hi {{name}}", "<p>Hello {{name}}, your code is {{code}}</p>");
        when(templates.findById(7L)).thenReturn(Optional.of(template));
        stubPersistenceAssigningIds();

        Long campaignId = service.send(new TransactionalRequest(
                7L, "user@example.com", Map.of("name", "Alice", "code", "1234")));

        assertThat(campaignId).isEqualTo(CAMPAIGN_ID);

        // The campaign handed to the repository already carries the rendered content.
        ArgumentCaptor<Campaign> campaignCaptor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(campaignCaptor.capture());
        Campaign saved = campaignCaptor.getValue();
        assertThat(saved.getSubject()).isEqualTo("Hi Alice");
        assertThat(saved.getBody()).isEqualTo("<p>Hello Alice, your code is 1234</p>");
        assertThat(saved.getStatus()).isEqualTo(CampaignStatus.QUEUED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void send_savesExactlyOneMessageAndEnqueuesItsId() {
        Template template = Template.create("welcome", "Subject", "<p>Body</p>");
        when(templates.findById(7L)).thenReturn(Optional.of(template));
        stubPersistenceAssigningIds();

        service.send(new TransactionalRequest(7L, "user@example.com", Map.of()));

        ArgumentCaptor<List<MailMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(messages).saveAll(msgCaptor.capture());
        assertThat(msgCaptor.getValue()).hasSize(1);
        MailMessage message = msgCaptor.getValue().get(0);
        assertThat(message.getRecipient()).isEqualTo("user@example.com");
        assertThat(message.getCampaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(message.getStatus()).isEqualTo(MessageStatus.PENDING);

        verify(mailQueue).enqueue(MESSAGE_ID);
        verifyNoMoreInteractions(mailQueue);
    }

    @Test
    void send_withNullVariables_rendersPlaceholdersAsEmpty() {
        Template template = Template.create("welcome", "Hi {{name}}", "<p>Hello {{name}}</p>");
        when(templates.findById(7L)).thenReturn(Optional.of(template));
        stubPersistenceAssigningIds();

        service.send(new TransactionalRequest(7L, "user@example.com", null));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("Hi ");
        assertThat(captor.getValue().getBody()).isEqualTo("<p>Hello </p>");
    }

    @Test
    void send_withUnknownTemplateId_throwsNoSuchElement() {
        when(templates.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(new TransactionalRequest(99L, "user@example.com", Map.of())))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");

        verifyNoInteractions(campaigns, messages, mailQueue);
    }

    @Test
    void send_withInvalidRecipient_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.send(new TransactionalRequest(7L, "not-an-email", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.send(new TransactionalRequest(7L, null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);

        // Recipient validation happens before any repository access.
        verifyNoInteractions(templates, campaigns, messages, mailQueue);
    }
}
