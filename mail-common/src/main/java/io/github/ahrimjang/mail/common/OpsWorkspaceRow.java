package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * 플랫폼 운영자 화면의 워크스페이스 한 줄 — 테넌트를 넘나드는 목록이라 콘솔의
 * {@link WorkspaceView}(자기 워크스페이스 전용)와 분리한다.
 *
 * @param ownerEmail       첫 ADMIN 의 이메일(문의 대응 연락처). 없으면 null
 * @param ownerVerified    그 계정의 가입 이메일 인증 여부
 * @param monthlySent      이번 달 발송 성공 수
 * @param monthlySendLimit 플랜 월 한도. null = 무제한
 * @param attempted30d     최근 30일 발송 시도(SENT+BOUNCED)
 * @param bounced30d       최근 30일 바운스
 * @param lastActivityAt   최근 메시지 종료 시각(발송 활동의 근사치). 없으면 null
 * @param suspendedAt      발송 정지 시각. null = 정상
 */
public record OpsWorkspaceRow(
        Long id,
        String name,
        String plan,
        Instant createdAt,
        long memberCount,
        String ownerEmail,
        boolean ownerVerified,
        long monthlySent,
        Long monthlySendLimit,
        long attempted30d,
        long bounced30d,
        Instant lastActivityAt,
        Instant suspendedAt,
        String suspensionReason,
        boolean billingRegistered,
        boolean apiKeyIssued
) {
    /** 최근 30일 바운스율(0~1). 시도가 없으면 0. */
    public double bounceRate30d() {
        return attempted30d == 0 ? 0 : (double) bounced30d / attempted30d;
    }
}
