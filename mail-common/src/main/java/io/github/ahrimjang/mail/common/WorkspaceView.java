package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * A tenant workspace as the admin console sees it. {@code monthlySent} is the
 * number usage-based billing charges against.
 */
public record WorkspaceView(
        Long id,
        String name,
        Integer sendRatePerSec,
        Instant createdAt,
        long memberCount,
        long monthlySent
) {
}
