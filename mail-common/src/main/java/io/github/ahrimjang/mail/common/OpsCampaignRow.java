package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * 플랫폼 운영자 화면의 캠페인 한 줄 — 전 테넌트 검색 결과. 어느 워크스페이스 것인지
 * 이름까지 붙여 준다(어뷰즈 신고 대응 시 테넌트 식별용).
 *
 * @param enqueuedAt   큐에 릴리스된 시각. null = 예약 대기
 * @param completedAt  끝난 시각(완료·중단). null = 진행 중
 */
public record OpsCampaignRow(
        Long id,
        Long workspaceId,
        String workspaceName,
        String name,
        String subject,
        CampaignStatus status,
        long total,
        long sent,
        long failed,
        long bounced,
        Instant createdAt,
        Instant enqueuedAt,
        Instant completedAt,
        String createdBy
) {
}
