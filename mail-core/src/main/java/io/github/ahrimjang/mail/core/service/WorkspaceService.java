package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CreateWorkspaceUserRequest;
import io.github.ahrimjang.mail.common.UpdateUserRoleRequest;
import io.github.ahrimjang.mail.common.UpdateWorkspaceRequest;
import io.github.ahrimjang.mail.common.WorkspaceUserView;
import io.github.ahrimjang.mail.common.WorkspaceView;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.PasswordHasher;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Admin console of a tenant workspace: settings (rename + BYO connector
 * selection) and member management (ADMIN runs the workspace, OPERATOR runs
 * campaigns). Every mutation requires the ADMIN role; reads are open to any
 * member so the console can show where it is operating.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final MailMessageRepository messages;
    private final PasswordHasher hasher;
    private final WorkspaceContext ctx;

    public WorkspaceService(WorkspaceRepository workspaces, UserRepository users,
                            MailMessageRepository messages,
                            PasswordHasher hasher, WorkspaceContext ctx) {
        this.workspaces = workspaces;
        this.users = users;
        this.messages = messages;
        this.hasher = hasher;
        this.ctx = ctx;
    }

    /** The acting user's workspace. */
    public WorkspaceView current() {
        return toView(requireWorkspace());
    }

    /** Rename and/or change the BYO connector selection (ADMIN only). */
    public WorkspaceView update(UpdateWorkspaceRequest request) {
        requireAdmin();
        Workspace workspace = requireWorkspace();
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (!Workspace.SMTP_PROVIDERS.contains(request.smtpProvider())) {
            throw new IllegalArgumentException("unknown smtp provider: " + request.smtpProvider());
        }
        if (!Workspace.STORAGE_PROVIDERS.contains(request.storageProvider())) {
            throw new IllegalArgumentException("unknown storage provider: " + request.storageProvider());
        }
        workspace.setName(request.name().trim());
        workspace.setSmtpProvider(request.smtpProvider());
        workspace.setStorageProvider(request.storageProvider());
        return toView(workspaces.save(workspace));
    }

    /** Members of the acting user's workspace (ADMIN only). */
    public List<WorkspaceUserView> members() {
        requireAdmin();
        return users.findByWorkspaceId(ctx.currentWorkspaceId()).stream()
                .map(WorkspaceService::toUserView)
                .toList();
    }

    /** Create a member account inside this workspace (ADMIN only). */
    public WorkspaceUserView addMember(CreateWorkspaceUserRequest request) {
        requireAdmin();
        if (request.email() == null || request.email().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("email and password are required");
        }
        String role = requireRole(request.role());
        if (users.existsByEmail(request.email())) {
            throw new IllegalStateException("email already registered: " + request.email());
        }
        User user = User.register(request.email(), hasher.hash(request.password()), request.displayName());
        user.setWorkspaceId(ctx.currentWorkspaceId());
        user.setRole(role);
        return toUserView(users.save(user));
    }

    /**
     * Change a member's role (ADMIN only). The last ADMIN cannot be demoted —
     * a workspace must always have someone who can run it.
     */
    public WorkspaceUserView changeRole(Long userId, UpdateUserRoleRequest request) {
        requireAdmin();
        String role = requireRole(request.role());
        User user = users.findById(userId)
                .filter(u -> u.getWorkspaceId().equals(ctx.currentWorkspaceId()))
                .orElseThrow(() -> new NoSuchElementException("user not found: " + userId));
        if ("OPERATOR".equals(role) && "ADMIN".equals(user.getRole()) && countAdmins() <= 1) {
            throw new IllegalStateException("cannot demote the last admin");
        }
        user.setRole(role);
        return toUserView(users.save(user));
    }

    private long countAdmins() {
        return users.findByWorkspaceId(ctx.currentWorkspaceId()).stream()
                .filter(u -> "ADMIN".equals(u.getRole()))
                .count();
    }

    private static String requireRole(String role) {
        if (!"ADMIN".equals(role) && !"OPERATOR".equals(role)) {
            throw new IllegalArgumentException("role must be ADMIN or OPERATOR");
        }
        return role;
    }

    private void requireAdmin() {
        if (!ctx.isAdmin()) {
            throw new ForbiddenException("workspace admin role required");
        }
    }

    private Workspace requireWorkspace() {
        return workspaces.findById(ctx.currentWorkspaceId())
                .orElseThrow(() -> new NoSuchElementException("workspace not found"));
    }

    private WorkspaceView toView(Workspace w) {
        return new WorkspaceView(w.getId(), w.getName(), w.getSmtpProvider(), w.getStorageProvider(),
                w.getCreatedAt(), users.countByWorkspaceId(w.getId()), monthlySent(w.getId()));
    }

    /**
     * Usage meter: SENT mail this calendar month (local zone) — the number a
     * plan/quota would bill against. Computed on read; a count over an indexed
     * join is cheap at this scale and always current.
     */
    private long monthlySent(Long workspaceId) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.Instant monthStart = java.time.LocalDate.now(zone)
                .withDayOfMonth(1).atStartOfDay(zone).toInstant();
        return messages.countSentByWorkspaceSince(workspaceId, monthStart);
    }

    private static WorkspaceUserView toUserView(User u) {
        return new WorkspaceUserView(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.getCreatedAt());
    }
}
