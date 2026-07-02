package io.github.ahrimjang.mail.common;

import java.util.Map;

/**
 * Request to send a single transactional mail: a template rendered with the
 * given variables and delivered to one recipient through the regular pipeline.
 *
 * @param templateId template to render
 * @param recipient  destination address
 * @param variables  values substituted into the template's {@code {{variable}}} placeholders
 */
public record TransactionalRequest(
        Long templateId,
        String recipient,
        Map<String, String> variables
) {
}
