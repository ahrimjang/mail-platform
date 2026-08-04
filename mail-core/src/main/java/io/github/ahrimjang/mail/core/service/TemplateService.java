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
    private final io.github.ahrimjang.mail.core.port.BuiltinVisibilityRepository hiddenBuiltins;

    /** Who is acting, for which tenant — resolved by the API adapter per request. */
    private final WorkspaceContext ctx;

    public TemplateService(TemplateRepository templates, TemplateRenderer renderer,
                           WorkspaceContext ctx,
                           io.github.ahrimjang.mail.core.port.BuiltinVisibilityRepository hiddenBuiltins) {
        this.ctx = ctx;
        this.templates = templates;
        this.renderer = renderer;
        this.hiddenBuiltins = hiddenBuiltins;
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
        // Built-ins are shared by every workspace — editing one would leak the
        // change across tenants. They are read-only: copy, then edit the copy.
        if (template.isBuiltin()) {
            throw new IllegalStateException("built-in templates are read-only — copy them first: " + id);
        }
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
        // 이 워크스페이스가 숨긴 빌트인은 목록(캠페인·이메일 만들기 포함)에서 뺀다.
        var hidden = hiddenBuiltins.hiddenTemplateIds(ctx.currentWorkspaceId());
        return templates.findVisibleToWorkspace(ctx.currentWorkspaceId()).stream()
                .filter(t -> !(t.isBuiltin() && hidden.contains(t.getId())))
                .map(this::toView)
                .toList();
    }

    /** 이 워크스페이스가 숨긴 빌트인 목록 — 복원 UI 용. */
    public List<TemplateView> listHidden() {
        var hidden = hiddenBuiltins.hiddenTemplateIds(ctx.currentWorkspaceId());
        return templates.findVisibleToWorkspace(ctx.currentWorkspaceId()).stream()
                .filter(t -> t.isBuiltin() && hidden.contains(t.getId()))
                .map(this::toView)
                .toList();
    }

    /**
     * 빌트인을 이 워크스페이스의 목록에서 숨긴다. 전역 자산이라 삭제는 불가하고
     * (시더가 되살림), 숨김은 내 화면에서만 빠지는 표시용 기록이다. 이미 캠페인·
     * 이메일로 복사된 내용에는 영향이 없다.
     */
    public void hide(Long id) {
        if (!load(id).isBuiltin()) {
            throw new IllegalStateException("내 템플릿은 숨기기 대신 삭제할 수 있어요: " + id);
        }
        hiddenBuiltins.hide(ctx.currentWorkspaceId(), id);
    }

    /** 숨긴 빌트인을 다시 목록에 표시한다. */
    public void unhide(Long id) {
        hiddenBuiltins.unhide(ctx.currentWorkspaceId(), id);
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

    /**
     * Copy any visible template (typically a built-in) into the acting
     * workspace as an editable duplicate — the copy-on-write half of the
     * read-only built-ins rule.
     */
    public TemplateView copy(Long id) {
        Template source = load(id);
        Template copy = Template.create(source.getName() + " (복사)", source.getSubject(), source.getHtmlBody());
        copy.setWorkspaceId(ctx.currentWorkspaceId());
        return toView(templates.save(copy));
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
