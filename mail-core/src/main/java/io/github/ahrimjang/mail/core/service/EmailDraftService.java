package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.EmailDraftView;
import io.github.ahrimjang.mail.common.SaveEmailDraftRequest;
import io.github.ahrimjang.mail.core.domain.EmailDraft;
import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.EmailDraftRepository;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 이메일(캠페인용 콘텐츠) 유스케이스. 템플릿은 재사용 자산이고, 이메일은 그걸
 * "불러와서"(내용 복사) 다듬은 이번 발송분이다 — 캠페인은 이메일을 선택한다.
 *
 * <p>테넌트 격리: 목록은 현재 워크스페이스로 스코프하고, by-id 접근은 소유 검증
 * 실패 시 404(존재를 숨김 — V16 원칙).
 */
@Service
public class EmailDraftService {

    private final EmailDraftRepository drafts;
    private final TemplateRepository templates;
    private final WorkspaceContext ctx;

    public EmailDraftService(EmailDraftRepository drafts, TemplateRepository templates,
                             WorkspaceContext ctx) {
        this.drafts = drafts;
        this.templates = templates;
        this.ctx = ctx;
    }

    public List<EmailDraftView> list() {
        return drafts.findByWorkspaceId(ctx.currentWorkspaceId()).stream()
                .map(EmailDraftService::toView)
                .toList();
    }

    public EmailDraftView get(Long id) {
        return toView(ownedOrThrow(id));
    }

    /**
     * 생성 — templateId 가 있으면 그 템플릿의 내용을 복사해 시작한다(요청 필드가
     * 있으면 그것이 우선). 빈 문서 시작이면 name/subject/htmlBody 필수.
     */
    public EmailDraftView create(SaveEmailDraftRequest request) {
        String name = request.name();
        String subject = request.subject();
        String htmlBody = request.htmlBody();
        Long sourceTemplateId = null;

        if (request.templateId() != null) {
            Template template = templates.findById(request.templateId())
                    .filter(this::templateVisible)
                    .orElseThrow(() -> new NoSuchElementException("template not found: " + request.templateId()));
            sourceTemplateId = template.getId();
            if (name == null || name.isBlank()) name = template.getName();
            if (subject == null || subject.isBlank()) subject = template.getSubject();
            if (htmlBody == null || htmlBody.isBlank()) htmlBody = template.getHtmlBody();
        }
        if (name == null || name.isBlank() || subject == null || subject.isBlank()
                || htmlBody == null || htmlBody.isBlank()) {
            throw new IllegalArgumentException("이름, 제목, 본문이 필요합니다.");
        }
        EmailDraft saved = drafts.save(EmailDraft.of(
                ctx.currentWorkspaceId(), uniqueName(name.trim()), subject.trim(), htmlBody, sourceTemplateId));
        return toView(saved);
    }

    public EmailDraftView update(Long id, SaveEmailDraftRequest request) {
        if (request.name() == null || request.name().isBlank()
                || request.subject() == null || request.subject().isBlank()
                || request.htmlBody() == null || request.htmlBody().isBlank()) {
            throw new IllegalArgumentException("이름, 제목, 본문이 필요합니다.");
        }
        EmailDraft draft = ownedOrThrow(id);
        draft.setName(request.name().trim());
        draft.setSubject(request.subject().trim());
        draft.setHtmlBody(request.htmlBody());
        draft.touch();
        return toView(drafts.save(draft));
    }

    public void delete(Long id) {
        drafts.deleteById(ownedOrThrow(id).getId());
    }

    /**
     * 같은 이름이 이미 있으면 "이름 2", "이름 3"… 으로 자동 넘버링 — 기본 이름
     * ("새 이메일")이 구분 불가하게 쌓이는 것을 막는다. 수정 시에는 강제하지 않는다.
     */
    private String uniqueName(String base) {
        var existing = drafts.findByWorkspaceId(ctx.currentWorkspaceId()).stream()
                .map(EmailDraft::getName)
                .collect(java.util.stream.Collectors.toSet());
        if (!existing.contains(base)) {
            return base;
        }
        int n = 2;
        while (existing.contains(base + " " + n)) {
            n++;
        }
        return base + " " + n;
    }

    /** 캠페인 등록의 스냅샷 소스 — 소유 검증 포함. */
    public EmailDraft ownedOrThrow(Long id) {
        return drafts.findById(id)
                .filter(d -> d.getWorkspaceId().equals(ctx.currentWorkspaceId()))
                .orElseThrow(() -> new NoSuchElementException("email not found: " + id));
    }

    /**
     * 캠페인 목록의 매핑 표시용 이름 — 삭제된 이메일이면 null (소프트 참조).
     * 캠페인 행 자체가 이미 테넌트 스코프이므로 여기서 소유 재검증은 하지 않는다.
     */
    public String displayNameOf(Long id) {
        return drafts.findById(id).map(EmailDraft::getName).orElse(null);
    }

    /** 빌트인(전역) 또는 내 워크스페이스의 템플릿만 보인다 — CampaignService 와 동일 규칙. */
    private boolean templateVisible(Template t) {
        return t.getWorkspaceId() == null || t.getWorkspaceId().equals(ctx.currentWorkspaceId());
    }

    private static EmailDraftView toView(EmailDraft d) {
        return new EmailDraftView(d.getId(), d.getName(), d.getSubject(), d.getHtmlBody(),
                d.getSourceTemplateId(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
