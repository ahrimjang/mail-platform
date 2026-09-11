package io.github.ahrimjang.mail.common;

import java.util.List;

/**
 * 플랫폼 운영자 화면의 워크스페이스 상세 — 요약 한 줄 + 멤버 + 최근 캠페인 + 발송 속도.
 *
 * @param sendRatePerSec 현재 발송 속도 설정(건/초). null = 무제한
 * @param sendRateCap    플랜 상한. null = 협의
 */
public record OpsWorkspaceDetail(
        OpsWorkspaceRow summary,
        Integer sendRatePerSec,
        Integer sendRateCap,
        List<WorkspaceUserView> members,
        List<OpsCampaignRow> recentCampaigns
) {
}
