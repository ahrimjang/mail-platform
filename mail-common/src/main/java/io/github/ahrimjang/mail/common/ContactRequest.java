package io.github.ahrimjang.mail.common;

import java.util.Map;

/**
 * Request to create a contact.
 *
 * @param email      contact address; must be unique
 * @param firstName  optional first name
 * @param lastName   optional last name
 * @param attributes optional free-form key/value attributes used for personalization
 */
public record ContactRequest(
        String email,
        String firstName,
        String lastName,
        Map<String, String> attributes
) {
}
