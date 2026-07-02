package io.github.ahrimjang.mail.common;

import java.time.Instant;

/**
 * Read model of a mail template returned to API clients.
 *
 * @param id        template identifier
 * @param name      human-readable template name
 * @param subject   subject line, may contain {@code {{variable}}} placeholders
 * @param htmlBody  HTML body, may contain {@code {{variable}}} placeholders
 * @param createdAt when the template was first created
 * @param updatedAt when the template was last modified
 */
public record TemplateView(
        Long id,
        String name,
        String subject,
        String htmlBody,
        Instant createdAt,
        Instant updatedAt
) {
}
