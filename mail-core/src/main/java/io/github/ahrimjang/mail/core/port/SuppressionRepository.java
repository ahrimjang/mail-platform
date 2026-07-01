package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Suppression;

/**
 * Persistence port for the global suppression list. Implemented by an infra adapter.
 */
public interface SuppressionRepository {

    void save(Suppression s);

    boolean existsByEmail(String email);
}
