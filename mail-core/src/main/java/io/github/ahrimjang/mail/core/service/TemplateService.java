package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.RenderedTemplate;
import io.github.ahrimjang.mail.common.TemplateRequest;
import io.github.ahrimjang.mail.common.TemplateView;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Use cases for authoring and previewing reusable mail templates.
 *
 * <p>Templates are the content source for campaigns (snapshotted at create
 * time) and transactional sends (rendered immediately). Preview renders the
 * template with caller-supplied variables without persisting anything.
 */
@Service
public class TemplateService {

    private final TemplateRepository templates;
    private final TemplateRenderer renderer;

    public TemplateService(TemplateRepository templates, TemplateRenderer renderer) {
        this.templates = templates;
        this.renderer = renderer;
    }

    public TemplateView create(TemplateRequest request) {
        validate(request);
        Template saved = templates.save(Template.create(request.name(), request.subject(), request.htmlBody()));
        return toView(saved);
    }

    public TemplateView update(Long id, TemplateRequest request) {
        validate(request);
        Template template = load(id);
        template.setName(request.name());
        template.setSubject(request.subject());
        template.setHtmlBody(request.htmlBody());
        template.setUpdatedAt(Instant.now());
        return toView(templates.save(template));
    }

    public TemplateView get(Long id) {
        return toView(load(id));
    }

    public List<TemplateView> list() {
        return templates.findAll().stream()
                .map(this::toView)
                .toList();
    }

    public void delete(Long id) {
        templates.deleteById(id);
    }

    /** Render the template's subject and body with the given variables, without persisting. */
    public RenderedTemplate preview(Long id, Map<String, String> vars) {
        Template template = load(id);
        return new RenderedTemplate(
                renderer.render(template.getSubject(), vars),
                renderer.render(template.getHtmlBody(), vars)
        );
    }

    private Template load(Long id) {
        return templates.findById(id)
                .orElseThrow(() -> new NoSuchElementException("template not found: " + id));
    }

    private void validate(TemplateRequest request) {
        if (request.name() == null || request.name().isBlank()
                || request.subject() == null || request.subject().isBlank()
                || request.htmlBody() == null || request.htmlBody().isBlank()) {
            throw new IllegalArgumentException("name, subject and htmlBody are required");
        }
    }

    private TemplateView toView(Template template) {
        return new TemplateView(
                template.getId(),
                template.getName(),
                template.getSubject(),
                template.getHtmlBody(),
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
