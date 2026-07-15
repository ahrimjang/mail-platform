package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Suppression;

import java.util.Optional;

/**
 * Persistence port for the global suppression list. Implemented by an infra adapter.
 */
public interface SuppressionRepository {

    void save(Suppression s);

    boolean existsByEmail(String email);

    Optional<Suppression> findByEmail(String email);

    /** Remove the address from the suppression list; a no-op if not present. */
    void deleteByEmail(String email);

    /** Total number of suppressed addresses (dashboard audience health). */
    long count();

    /** Suppressions grouped by reason ("bounce"/"unsubscribe"/"manual"), largest first. */
    java.util.List<ReasonCount> countByReason();

    /** Same breakdown, restricted to entries created since {@code since}. */
    java.util.List<ReasonCount> countByReasonSince(java.time.Instant since);

    /** One reason's suppression count. */
    record ReasonCount(String reason, long count) {
    }
}
