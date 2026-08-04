package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.EmailDraft;

import java.util.List;
import java.util.Optional;

/** 이메일(캠페인용 콘텐츠) 저장소 포트. */
public interface EmailDraftRepository {

    EmailDraft save(EmailDraft draft);

    Optional<EmailDraft> findById(Long id);

    /** 워크스페이스의 이메일 전부 — 최근 수정 순. */
    List<EmailDraft> findByWorkspaceId(Long workspaceId);

    void deleteById(Long id);
}
