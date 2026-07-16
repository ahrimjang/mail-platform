package io.github.ahrimjang.mail.core.port;

/**
 * Who is acting, on behalf of which tenant. Console-facing services read this
 * instead of threading a workspaceId parameter through every signature; the
 * API adapter resolves it from the authenticated request. Worker-side code
 * never uses it — background paths derive the tenant from the campaign row.
 */
public interface WorkspaceContext {

    /** Workspace of the authenticated user; throws if there is no request context. */
    Long currentWorkspaceId();

    /** True when the authenticated user is a workspace ADMIN. */
    boolean isAdmin();

    /** Email of the authenticated user. */
    String currentUserEmail();
}
