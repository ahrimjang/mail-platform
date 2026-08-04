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
        WorkspaceEntity entity = new WorkspaceEntity(
                workspace.getId(), workspace.getName(), workspace.getPlan().name(),
                workspace.getSendRatePerSec(), workspace.getBillingKey(), workspace.getCreatedAt());
        entity.setApiKey(workspace.getApiKey());
        return toDomain(jpa.save(entity));
    }

    @Override
    public Optional<Workspace> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Workspace> findByApiKey(String apiKey) {
        return jpa.findByApiKey(apiKey).map(this::toDomain);
    }

    private Workspace toDomain(WorkspaceEntity e) {
        Workspace w = new Workspace();
        w.setId(e.getId());
        w.setName(e.getName());
        w.setPlan(io.github.ahrimjang.mail.core.domain.Plan.valueOf(e.getPlan()));
        w.setSendRatePerSec(e.getSendRatePerSec());
        w.setBillingKey(e.getBillingKey());
        w.setApiKey(e.getApiKey());
        w.setCreatedAt(e.getCreatedAt());
        return w;
    }
}
