package io.github.ahrimjang.mail.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ahrimjang.mail.common.BounceNotification;
import io.github.ahrimjang.mail.common.BounceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates an SES event document (the JSON travelling inside the SNS
 * envelope's {@code Message}) into the platform's normalized
 * {@link BounceNotification}s — one per affected recipient. Everything the
 * rest of the system knows about bounces stays provider-agnostic;
 * {@link io.github.ahrimjang.mail.core.service.BounceService} is untouched.
 *
 * <p>Mapping: SES {@code Permanent} bounce → HARD_BOUNCE, {@code Transient}/
 * {@code Undetermined} → SOFT_BOUNCE, complaints → COMPLAINT. Delivery and
 * unknown notification types produce nothing. The correlation id is the
 * {@code X-Mail-Message-Id} header SES echoes back from the original send.
 */
@Component
public class SesNotificationParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<BounceNotification> parse(String sesMessageJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(sesMessageJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("not an SES notification document", e);
        }
        String type = text(root, "notificationType");
        Long messageId = correlationId(root.path("mail"));

        if ("Bounce".equals(type)) {
            JsonNode bounce = root.path("bounce");
            BounceType bounceType = "Permanent".equals(text(bounce, "bounceType"))
                    ? BounceType.HARD_BOUNCE
                    : BounceType.SOFT_BOUNCE;
            List<BounceNotification> out = new ArrayList<>();
            for (JsonNode recipient : bounce.path("bouncedRecipients")) {
                String reason = text(recipient, "diagnosticCode");
                out.add(new BounceNotification(
                        text(recipient, "emailAddress"),
                        bounceType,
                        reason != null ? reason : text(bounce, "bounceSubType"),
                        messageId));
            }
            return out;
        }
        if ("Complaint".equals(type)) {
            JsonNode complaint = root.path("complaint");
            List<BounceNotification> out = new ArrayList<>();
            for (JsonNode recipient : complaint.path("complainedRecipients")) {
                out.add(new BounceNotification(
                        text(recipient, "emailAddress"),
                        BounceType.COMPLAINT,
                        text(complaint, "complaintFeedbackType"),
                        messageId));
            }
            return out;
        }
        // Delivery and anything unrecognized: nothing to suppress.
        return List.of();
    }

    /** Our own message id, echoed back by SES in the original mail's headers. */
    private static Long correlationId(JsonNode mail) {
        for (JsonNode header : mail.path("headers")) {
            if ("X-Mail-Message-Id".equalsIgnoreCase(text(header, "name"))) {
                try {
                    return Long.parseLong(text(header, "value"));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
