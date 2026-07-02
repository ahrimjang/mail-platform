package io.github.ahrimjang.mail.common;

/**
 * A template's subject and body after {@code {{variable}}} substitution.
 *
 * @param subject  rendered subject line
 * @param htmlBody rendered HTML body
 */
public record RenderedTemplate(
        String subject,
        String htmlBody
) {
}
