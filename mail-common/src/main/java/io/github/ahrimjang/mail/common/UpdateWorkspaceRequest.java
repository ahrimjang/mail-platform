package io.github.ahrimjang.mail.common;

/**
 * Admin-side workspace settings: rename plus the BYO connector selection
 * (which SMTP relay / file storage the tenant brings — selection only for
 * now, the actual wiring lands with the SES/S3 integrations).
 *
 * @param sendRatePerSec send throttle in msgs/sec (match it to the BYO
 *                       provider's rate limit); null = unlimited
 */
public record UpdateWorkspaceRequest(
        String name,
        String smtpProvider,
        String storageProvider,
        Integer sendRatePerSec
) {
}
