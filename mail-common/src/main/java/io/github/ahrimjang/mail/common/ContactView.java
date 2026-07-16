package io.github.ahrimjang.mail.common;

import java.time.Instant;
import java.util.Map;

/**
 * Read model of a contact returned to API clients.
 *
 * @param id         contact identifier
 * @param email      contact address
 * @param firstName  optional first name
 * @param lastName   optional last name
 * @param attributes free-form key/value attributes used for personalization
 * @param createdAt  when the contact was created
 */
public record ContactView(
        Long id,
        String email,
        String firstName,
        String lastName,
        Map<String, String> attributes,
        Instant createdAt,
        String consentSource,
        Instant consentedAt
) {
}
