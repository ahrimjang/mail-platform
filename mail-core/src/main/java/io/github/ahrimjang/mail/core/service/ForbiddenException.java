package io.github.ahrimjang.mail.core.service;

/** The authenticated user lacks the role this operation requires (maps to 403). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
