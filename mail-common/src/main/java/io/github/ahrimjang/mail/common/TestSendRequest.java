package io.github.ahrimjang.mail.common;

/**
 * "내게 먼저 보내기": mail the compose form's current content (direct
 * subject/body or a template) to one address, rendered with sample variables.
 * Testing variant B is just this request with B's content in the same fields.
 */
public record TestSendRequest(
        String recipient,
        String subject,
        String body,
        Long templateId,
        String senderName,
        String senderEmail
) {
}
