package io.github.ahrimjang.mail.common;

import java.time.Instant;

/** 플랫폼 운영자 감사 로그 한 줄 — 누가·언제·어느 테넌트/캠페인에·무엇을. */
public record OpsAuditEntry(
        Long id,
        String actorEmail,
        String action,
        Long workspaceId,
        Long campaignId,
        String detail,
        Instant createdAt
) {
}
