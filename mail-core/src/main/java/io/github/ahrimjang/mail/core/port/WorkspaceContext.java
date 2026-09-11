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

    /**
     * 플랫폼 운영자인가(users.platform_role, V35) — 테넌트 격리를 넘어 모든 워크스페이스를
     * 다루는 /ops 화면의 게이트. 워크스페이스 ADMIN 과는 무관하다.
     */
    boolean isPlatformOperator();
}
