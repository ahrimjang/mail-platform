package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.Suppression;
import io.github.ahrimjang.mail.core.port.SuppressionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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

    @Override
    public Optional<Suppression> findByEmail(String email) {
        return jpa.findByEmail(email).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deleteByEmail(String email) {
        jpa.deleteByEmail(email);
    }

    private Suppression toDomain(SuppressionEntity e) {
        Suppression s = new Suppression();
        s.setEmail(e.getEmail());
        s.setReason(e.getReason());
        s.setCreatedAt(e.getCreatedAt());
        return s;
    }
}
