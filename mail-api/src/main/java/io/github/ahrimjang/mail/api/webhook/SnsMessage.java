package io.github.ahrimjang.mail.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The SNS HTTP(S) delivery envelope. SNS posts one JSON document per event;
 * the interesting SES payload travels stringified inside {@code Message}.
 * Only the fields the webhook needs are mapped.
 */
public record SnsMessage(
        String type,
        String messageId,
        String topicArn,
        String subject,
        String message,
        String timestamp,
        String token,
        String subscribeUrl,
        String signatureVersion,
        String signature,
        String signingCertUrl
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parse the raw POST body (SNS sends it as text/plain). */
    public static SnsMessage parse(String rawBody) {
        try {
            JsonNode n = MAPPER.readTree(rawBody);
            return new SnsMessage(
                    text(n, "Type"),
                    text(n, "MessageId"),
                    text(n, "TopicArn"),
                    text(n, "Subject"),
                    text(n, "Message"),
                    text(n, "Timestamp"),
                    text(n, "Token"),
                    text(n, "SubscribeURL"),
                    text(n, "SignatureVersion"),
                    text(n, "Signature"),
                    text(n, "SigningCertURL"));
        } catch (Exception e) {
            throw new IllegalArgumentException("not an SNS message body", e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * The exact byte string Amazon signed, per the SNS signature spec:
     * alternating {@code Name\nValue\n} lines in a fixed per-type order,
     * absent optional fields skipped.
     */
    public String stringToSign() {
        StringBuilder sb = new StringBuilder();
        if ("Notification".equals(type)) {
            append(sb, "Message", message);
            append(sb, "MessageId", messageId);
            append(sb, "Subject", subject);
            append(sb, "Timestamp", timestamp);
            append(sb, "TopicArn", topicArn);
            append(sb, "Type", type);
        } else { // SubscriptionConfirmation / UnsubscribeConfirmation
            append(sb, "Message", message);
            append(sb, "MessageId", messageId);
            append(sb, "SubscribeURL", subscribeUrl);
            append(sb, "Timestamp", timestamp);
            append(sb, "Token", token);
            append(sb, "TopicArn", topicArn);
            append(sb, "Type", type);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value != null) {
            sb.append(name).append('\n').append(value).append('\n');
        }
    }
}
