package io.github.ahrimjang.mail.common;

/** Admin creates a member account inside their own workspace. */
public record CreateWorkspaceUserRequest(
        String email,
        String password,
        String displayName,
        String role
) {
}
