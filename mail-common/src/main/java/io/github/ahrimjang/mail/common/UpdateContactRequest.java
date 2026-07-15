package io.github.ahrimjang.mail.common;

/** Rename a contact. The email is its identity (suppressions key on it) and cannot change. */
public record UpdateContactRequest(String firstName, String lastName) {
}
