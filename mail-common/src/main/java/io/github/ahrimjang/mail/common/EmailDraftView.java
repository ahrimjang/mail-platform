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
        Instant updatedAt,
        /**
         * 저장은 됐지만 발송 결과가 의도와 다를 수 있는 것들(미지원 변수·크기 초과).
         * 조회 응답에서는 항상 비어 있고, 저장 응답에서만 채워진다 — 경고는 "방금 저장한
         * 내용"에 대한 것이라 나중에 다시 꺼내 볼 값이 아니다.
         */
        java.util.List<String> warnings
) {
}
