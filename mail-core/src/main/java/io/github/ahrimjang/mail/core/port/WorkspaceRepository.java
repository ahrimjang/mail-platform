package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Workspace;

import java.util.Optional;

/** Persistence port for tenant workspaces. */
public interface WorkspaceRepository {

    Workspace save(Workspace workspace);

    Optional<Workspace> findById(Long id);
}
