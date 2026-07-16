package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.TestSendRequest;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestSendServiceTest {

    private static final long WS = 7L;

    @Mock
    private TemplateRepository templates;
    @Mock
    private MailSender sender;
    @Mock
    private WorkspaceContext ctx;

    private TestSendService service;

    @BeforeEach
    void setUp() {
        // Real renderer on purpose: sample-variable substitution is part of the contract.
        service = new TestSendService(templates, new TemplateRenderer(), sender, ctx);
        lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
    }

    @Test
    void send_rendersSampleVariablesAndPrefixesTheSubject() {
        service.send(new TestSendRequest("me@acme.com", "{{firstName}}님 소식", "<p>{{email}}</p>",
                null, "Acme", "hello@acme.io"));

        verify(sender).send("me@acme.com", "[테스트] 테스트님 소식", "<p>me@acme.com</p>",
                "test-send", "Acme", "hello@acme.io");
    }

    @Test
    void send_withTemplate_snapshotsItsContent() {
        Template template = Template.create("welcome", "환영해요 {{name}}", "<p>hi</p>");
        when(templates.findById(5L)).thenReturn(Optional.of(template));

        service.send(new TestSendRequest("me@acme.com", null, null, 5L, null, null));

        verify(sender).send(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void send_withAnotherTenantsTemplate_readsAsAbsent() {
        Template foreign = Template.create("비밀", "s", "b");
        foreign.setWorkspaceId(99L);
        when(templates.findById(5L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.send(new TestSendRequest("me@acme.com", null, null, 5L, null, null)))
                .isInstanceOf(NoSuchElementException.class);
        verify(sender, never()).send(any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_rejectsAnInvalidRecipientBeforeTouchingAnything() {
        assertThatThrownBy(() -> service.send(new TestSendRequest("not-an-email", "s", "b", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sender, never()).send(any(), any(), any(), any(), any(), any());
    }
}
