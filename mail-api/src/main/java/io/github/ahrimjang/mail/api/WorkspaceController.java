package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.CreateWorkspaceUserRequest;
import io.github.ahrimjang.mail.common.UpdateUserRoleRequest;
import io.github.ahrimjang.mail.common.UpdateWorkspaceRequest;
import io.github.ahrimjang.mail.common.WorkspaceUserView;
import io.github.ahrimjang.mail.common.WorkspaceView;
import io.github.ahrimjang.mail.core.service.ForbiddenException;
import io.github.ahrimjang.mail.core.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The tenant admin console: workspace settings (rename, BYO connector
 * selection) and member management. Mutations require the ADMIN role.
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final WorkspaceService workspace;

    public WorkspaceController(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    /** The acting user's workspace (any member). */
    @GetMapping
    public WorkspaceView current() {
        return workspace.current();
    }

    /** Rename / change providers (ADMIN). */
    @PutMapping
    public WorkspaceView update(@RequestBody UpdateWorkspaceRequest request) {
        return workspace.update(request);
    }

    /** Members of this workspace (ADMIN). */
    @GetMapping("/users")
    public List<WorkspaceUserView> members() {
        return workspace.members();
    }

    /** Create a member account (ADMIN). */
    @PostMapping("/users")
    public ResponseEntity<WorkspaceUserView> addMember(@RequestBody CreateWorkspaceUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workspace.addMember(request));
    }

    /** Change a member's role (ADMIN). */
    @PutMapping("/users/{id}/role")
    public WorkspaceUserView changeRole(@PathVariable Long id, @RequestBody UpdateUserRoleRequest request) {
        return workspace.changeRole(id, request);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
