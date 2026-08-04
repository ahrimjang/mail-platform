package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * 캠페인에 실제 쓰는 이메일 콘텐츠 — 템플릿(재사용 자산)과 구분되는 계층.
 * 에디터가 TemplateView 와 같은 필드(name/subject/htmlBody)를 쓰므로 모양을 맞춘다.
 */
public record EmailDraftView(
        Long id,
        String name,
        String subject,
        String htmlBody,
        Long sourceTemplateId,
        Instant createdAt,
        Instant updatedAt
) {
}
