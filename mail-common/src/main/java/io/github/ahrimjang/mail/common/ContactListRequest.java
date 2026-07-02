package io.github.ahrimjang.mail.common;

/**
 * Request to create a contact list.
 *
 * @param name        list name
 * @param description optional free-form description
 */
public record ContactListRequest(
        String name,
        String description
) {
}
