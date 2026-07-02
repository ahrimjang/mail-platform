package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * Read model of a contact list returned to API clients.
 *
 * @param id          list identifier
 * @param name        list name
 * @param description optional free-form description
 * @param memberCount number of contacts currently in the list
 * @param createdAt   when the list was created
 */
public record ContactListView(
        Long id,
        String name,
        String description,
        long memberCount,
        Instant createdAt
) {
}
