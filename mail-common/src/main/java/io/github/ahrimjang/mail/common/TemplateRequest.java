package io.github.ahrimjang.mail.common;

/**
 * Request to create or update a reusable mail template.
 *
 * @param name     human-readable template name
 * @param subject  subject line, may contain {@code {{variable}}} placeholders
 * @param htmlBody HTML body, may contain {@code {{variable}}} placeholders
 */
public record TemplateRequest(
        String name,
        String subject,
        String htmlBody
) {
}
