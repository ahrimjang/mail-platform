package io.github.ahrimjang.mail.common;

import java.util.List;

/**
 * Audience health for the analytics page: why addresses stop receiving mail.
 *
 * <p>{@code suppressionReasons} splits the global do-not-send list by reason
 * ("bounce" = undeliverable, "unsubscribe" = the recipient's own opt-out,
 * "manual" = operator action) with a total and the period's new entries;
 * {@code listOptOuts} counts per-list unsubscribes (memberships kept, sends
 * excluded at fan-out).
 */
public record AudienceHealthView(
        List<ReasonCount> suppressionReasons,
        List<ListOptOut> listOptOuts
) {
    /** One suppression reason: all-time total plus entries added in the period. */
    public record ReasonCount(String reason, long total, long recent) {}

    /** One list's recipient-initiated opt-out count. */
    public record ListOptOut(Long listId, String listName, long count) {}
}
