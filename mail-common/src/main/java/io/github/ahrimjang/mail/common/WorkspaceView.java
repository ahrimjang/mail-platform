package io.github.ahrimjang.mail.common;

import java.time.Instant;

/** A tenant workspace as the admin console sees it. */
public record WorkspaceView(
        Long id,
        String name,
        String smtpProvider,
        String storageProvider,
        Integer sendRatePerSec,
        Instant createdAt,
        long memberCount,
        long monthlySent
) {
}
