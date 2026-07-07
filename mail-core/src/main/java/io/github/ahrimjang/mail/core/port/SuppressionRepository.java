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
}
