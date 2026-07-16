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
        if (!jpa.existsByWorkspaceIdAndEmail(s.getWorkspaceId(), s.getEmail())) {
            SuppressionEntity entity = new SuppressionEntity(null, s.getEmail(), s.getReason(), s.getCreatedAt());
            entity.setWorkspaceId(s.getWorkspaceId());
            jpa.save(entity);
        }
    }

    @Override
    public boolean existsByWorkspaceAndEmail(Long workspaceId, String email) {
        return jpa.existsByWorkspaceIdAndEmail(workspaceId, email);
    }

    @Override
    public Optional<Suppression> findByWorkspaceAndEmail(Long workspaceId, String email) {
        return jpa.findByWorkspaceIdAndEmail(workspaceId, email).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deleteByWorkspaceAndEmail(Long workspaceId, String email) {
        jpa.deleteByWorkspaceIdAndEmail(workspaceId, email);
    }

    @Override
    public long countByWorkspace(Long workspaceId) {
        return jpa.countByWorkspaceId(workspaceId);
    }

    private Suppression toDomain(SuppressionEntity e) {
        Suppression s = new Suppression();
        s.setWorkspaceId(e.getWorkspaceId());
        s.setEmail(e.getEmail());
        s.setReason(e.getReason());
        s.setCreatedAt(e.getCreatedAt());
        return s;
    }

    @Override
    public java.util.List<ReasonCount> countByReason(Long workspaceId) {
        return jpa.countByReason(workspaceId).stream()
                .map(row -> new ReasonCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    @Override
    public java.util.List<ReasonCount> countByReasonSince(Long workspaceId, java.time.Instant since) {
        return jpa.countByReasonSince(workspaceId, since).stream()
                .map(row -> new ReasonCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    @Override
    public java.util.List<String> findSuppressedEmails(Long workspaceId, java.util.List<String> emails) {
        if (emails.isEmpty()) {
            return java.util.List.of();
        }
        return jpa.findSuppressedEmails(workspaceId, emails);
    }
}
