package io.github.ahrimjang.mail.core.port;

/**
 * Outbound port for the per-tenant send throttle. The dispatch path asks for
 * one token per message before claiming it; a denial means the workspace is
 * at its configured rate and the message should come back later.
 *
 * <p>The adapter owns the bucket state and must keep it correct across many
 * concurrent workers — same contract as the send/fan-out claims.
 */
public interface SendRateLimiter {

    /**
     * Take one send token for the workspace. Returns {@code true} when the
     * send may proceed now — including when the workspace has no rate limit
     * configured. {@code false} means throttled: try again later.
     */
    boolean tryAcquire(long workspaceId);
}
