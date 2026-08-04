package io.github.ahrimjang.mail.common;

/**
 * 외부 구독 신청 (공개 API, X-Api-Key 인증).
 *
 * @param email     구독자 이메일 (필수)
 * @param firstName 이름 (선택)
 * @param lastName  성 (선택)
 * @param listId    가입시킬 리스트 (선택 — 키 소유 워크스페이스의 리스트여야 함)
 */
public record SubscribeRequest(
        String email,
        String firstName,
        String lastName,
        Long listId
) {
}
