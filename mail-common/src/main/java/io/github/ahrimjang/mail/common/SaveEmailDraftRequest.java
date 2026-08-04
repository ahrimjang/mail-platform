package io.github.ahrimjang.mail.common;

/**
 * 이메일 생성/수정 요청.
 *
 * <p>생성 시 {@code templateId} 가 있으면 그 템플릿의 제목·본문을 복사해 시작한다
 * (나머지 필드는 넘긴 값이 우선). 수정 시 {@code templateId} 는 무시된다.
 */
public record SaveEmailDraftRequest(
        String name,
        String subject,
        String htmlBody,
        Long templateId
) {
}
