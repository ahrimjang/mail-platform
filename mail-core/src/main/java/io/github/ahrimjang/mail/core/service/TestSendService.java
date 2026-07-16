package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.TestSendRequest;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * "내게 먼저 보내기": renders the compose form's content with sample variables
 * and mails it synchronously to one address. Deliberately bypasses the whole
 * campaign pipeline — no campaign or message rows, no tracking rewrite, no
 * stats — because a test send must never pollute the numbers it exists to
 * protect. The subject is prefixed so a test can't be mistaken for the real
 * thing in an inbox.
 */
@Service
public class TestSendService {

    static final String SUBJECT_PREFIX = "[테스트] ";

    private final TemplateRepository templates;
    private final TemplateRenderer renderer;
    private final MailSender sender;
    private final WorkspaceContext ctx;

    public TestSendService(TemplateRepository templates, TemplateRenderer renderer,
                           MailSender sender, WorkspaceContext ctx) {
        this.templates = templates;
        this.renderer = renderer;
        this.sender = sender;
        this.ctx = ctx;
    }

    /** Send one rendered test mail; returns the recipient it went to. */
    public String send(TestSendRequest request) {
        if (request.recipient() == null || !request.recipient().contains("@")) {
            throw new IllegalArgumentException("valid recipient is required");
        }
        String subject;
        String body;
        if (request.templateId() != null) {
            Template template = templates.findById(request.templateId())
                    .filter(t -> t.getWorkspaceId() == null
                            || t.getWorkspaceId().equals(ctx.currentWorkspaceId()))
                    .orElseThrow(() -> new NoSuchElementException("template not found: " + request.templateId()));
            subject = template.getSubject();
            body = template.getHtmlBody();
        } else {
            subject = request.subject();
            body = request.body();
        }
        if (subject == null || subject.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("subject and body are required (direct or via template)");
        }

        // Sample variables so {{...}} placeholders render like a real delivery would.
        Map<String, String> vars = Map.of(
                "email", request.recipient(),
                "firstName", "테스트",
                "lastName", "수신자",
                "name", "테스트 수신자");
        sender.send(request.recipient(),
                SUBJECT_PREFIX + renderer.render(subject, vars),
                renderer.render(body, vars),
                "test-send",
                request.senderName(),
                request.senderEmail());
        return request.recipient();
    }
}
