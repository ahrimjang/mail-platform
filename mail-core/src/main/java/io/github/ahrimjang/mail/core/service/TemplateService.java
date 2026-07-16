package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.RenderedTemplate;
import io.github.ahrimjang.mail.common.TemplateRequest;
import io.github.ahrimjang.mail.common.TemplateView;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
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

    /** Who is acting, for which tenant — resolved by the API adapter per request. */
    private final WorkspaceContext ctx;

    public TemplateService(TemplateRepository templates, TemplateRenderer renderer,
                           WorkspaceContext ctx) {
        this.ctx = ctx;
        this.templates = templates;
        this.renderer = renderer;
    }

    public TemplateView create(TemplateRequest request) {
        validate(request);
        Template template = Template.create(request.name(), request.subject(), request.htmlBody());
        template.setWorkspaceId(ctx.currentWorkspaceId());
        return toView(templates.save(template));
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
        return templates.findVisibleToWorkspace(ctx.currentWorkspaceId()).stream()
                .map(this::toView)
                .toList();
    }

    public void delete(Long id) {
        // Built-ins are a permanent part of the console — offer reset, not delete
        // (the boot seeder would just resurrect a deleted one anyway).
        if (load(id).isBuiltin()) {
            throw new IllegalStateException("built-in templates cannot be deleted — reset them instead: " + id);
        }
        templates.deleteById(id);
    }

    /**
     * Inserts any built-in template that is missing (first boot, or new seeds
     * added in a release). Existing rows — including user-edited ones — are
     * left untouched, so edits survive restarts.
     *
     * @return number of templates inserted
     */
    public int seedBuiltins() {
        int inserted = 0;
        for (BuiltinTemplates.Seed seed : BuiltinTemplates.ALL) {
            if (templates.findByBuiltinKey(seed.key()).isEmpty()) {
                Template t = Template.create(seed.name(), seed.subject(), seed.htmlBody());
                t.setBuiltinKey(seed.key());
                templates.save(t);
                inserted++;
            }
        }
        return inserted;
    }

    /** Restores an edited built-in template back to its original seed content. */
    public TemplateView resetBuiltin(Long id) {
        Template template = load(id);
        if (!template.isBuiltin()) {
            throw new IllegalStateException("not a built-in template: " + id);
        }
        BuiltinTemplates.Seed seed = BuiltinTemplates.ALL.stream()
                .filter(s -> s.key().equals(template.getBuiltinKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "unknown built-in key: " + template.getBuiltinKey()));
        template.setName(seed.name());
        template.setSubject(seed.subject());
        template.setHtmlBody(seed.htmlBody());
        template.setUpdatedAt(Instant.now());
        return toView(templates.save(template));
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
                // Built-ins (workspace null) are shared; user templates only within their tenant.
                .filter(t -> t.getWorkspaceId() == null || t.getWorkspaceId().equals(ctx.currentWorkspaceId()))
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
                template.getUpdatedAt(),
                template.getBuiltinKey()
        );
    }
}
