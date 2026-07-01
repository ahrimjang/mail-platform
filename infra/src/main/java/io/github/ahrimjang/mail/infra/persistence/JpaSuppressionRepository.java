package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Repository;

/**
 * Adapter: implements the core {@link SuppressionRepository} port (the global
 * suppression list) over Spring Data JPA.
 */
@Repository
public class JpaSuppressionRepository implements SuppressionRepository {

    private final SuppressionJpaRepository jpa;

    public JpaSuppressionRepository(SuppressionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Suppression s) {
        if (!jpa.existsByEmail(s.getEmail())) {
            jpa.save(new SuppressionEntity(null, s.getEmail(), s.getReason(), s.getCreatedAt()));
        }
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }
}
