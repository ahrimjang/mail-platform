package io.github.ahrimjang.mail.worker;

import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.springframework.stereotype.Component;

/**
 * The worker has no authenticated request — background paths (dispatch,
 * fan-out, projections, schedulers) derive the tenant from the campaign row
 * instead. This bean only exists so console-facing services can be wired in
 * the worker's Spring context; calling it is a programming error.
 */
@Component
public class WorkerWorkspaceContext implements WorkspaceContext {

    @Override
    public Long currentWorkspaceId() {
        throw new IllegalStateException("worker has no request workspace context");
    }

    @Override
    public boolean isAdmin() {
        throw new IllegalStateException("worker has no request workspace context");
    }

    @Override
    public String currentUserEmail() {
        throw new IllegalStateException("worker has no request workspace context");
    }
}
