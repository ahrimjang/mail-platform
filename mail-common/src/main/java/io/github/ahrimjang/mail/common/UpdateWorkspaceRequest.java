package io.github.ahrimjang.mail.common;

/**
 * Admin-side workspace settings: rename plus the BYO connector selection
 * (which SMTP relay / file storage the tenant brings — selection only for
 * now, the actual wiring lands with the SES/S3 integrations).
 */
public record UpdateWorkspaceRequest(
        String name,
        String smtpProvider,
        String storageProvider
) {
}
