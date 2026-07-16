package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: implements the core {@link WorkspaceRepository} port over Spring Data JPA. */
@Repository
public class JpaWorkspaceRepository implements WorkspaceRepository {

    private final WorkspaceJpaRepository jpa;

    public JpaWorkspaceRepository(WorkspaceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Workspace save(Workspace workspace) {
        WorkspaceEntity saved = jpa.save(new WorkspaceEntity(
                workspace.getId(), workspace.getName(),
                workspace.getSmtpProvider(), workspace.getStorageProvider(),
                workspace.getCreatedAt()));
        return toDomain(saved);
    }

    @Override
    public Optional<Workspace> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    private Workspace toDomain(WorkspaceEntity e) {
        Workspace w = new Workspace();
        w.setId(e.getId());
        w.setName(e.getName());
        w.setSmtpProvider(e.getSmtpProvider());
        w.setStorageProvider(e.getStorageProvider());
        w.setCreatedAt(e.getCreatedAt());
        return w;
    }
}
