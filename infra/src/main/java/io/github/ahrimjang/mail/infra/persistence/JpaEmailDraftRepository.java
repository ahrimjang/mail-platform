package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.EmailDraft;
import io.github.ahrimjang.mail.core.port.EmailDraftRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 어댑터: 이메일(캠페인용 콘텐츠) 포트의 JPA 구현. */
@Repository
public class JpaEmailDraftRepository implements EmailDraftRepository {

    private final EmailDraftJpaRepository jpa;

    public JpaEmailDraftRepository(EmailDraftJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public EmailDraft save(EmailDraft draft) {
        EmailDraftEntity saved = jpa.save(new EmailDraftEntity(
                draft.getId(), draft.getWorkspaceId(), draft.getName(), draft.getSubject(),
                draft.getHtmlBody(), draft.getSourceTemplateId(),
                draft.getCreatedAt(), draft.getUpdatedAt()));
        draft.setId(saved.getId());
        return draft;
    }

    @Override
    public Optional<EmailDraft> findById(Long id) {
        return jpa.findById(id).map(JpaEmailDraftRepository::toDomain);
    }

    @Override
    public List<EmailDraft> findByWorkspaceId(Long workspaceId) {
        return jpa.findByWorkspaceIdOrderByUpdatedAtDesc(workspaceId).stream()
                .map(JpaEmailDraftRepository::toDomain)
                .toList();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }

    private static EmailDraft toDomain(EmailDraftEntity e) {
        EmailDraft d = new EmailDraft();
        d.setId(e.getId());
        d.setWorkspaceId(e.getWorkspaceId());
        d.setName(e.getName());
        d.setSubject(e.getSubject());
        d.setHtmlBody(e.getHtmlBody());
        d.setSourceTemplateId(e.getSourceTemplateId());
        d.setCreatedAt(e.getCreatedAt());
        d.setUpdatedAt(e.getUpdatedAt());
        return d;
    }
}
