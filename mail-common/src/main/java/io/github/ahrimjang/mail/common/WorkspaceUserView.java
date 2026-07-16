package io.github.ahrimjang.mail.common;

import java.time.Instant;

/** One member of a workspace, for the admin console's user management. */
public record WorkspaceUserView(
        Long id,
        String email,
        String displayName,
        String role,
        Instant createdAt
) {
}
