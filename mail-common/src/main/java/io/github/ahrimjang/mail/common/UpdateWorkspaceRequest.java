package io.github.ahrimjang.mail.common;

/**
 * Admin-side workspace settings. Sending infrastructure is platform-owned
 * (billing is by monthly send volume), so tenants configure only their name
 * and pacing.
 *
 * @param sendRatePerSec send throttle in msgs/sec; null = unlimited
 */
public record UpdateWorkspaceRequest(
        String name,
        Integer sendRatePerSec
) {
}
