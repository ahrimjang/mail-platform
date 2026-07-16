package io.github.ahrimjang.mail.api.webhook;

import io.github.ahrimjang.mail.common.BounceNotification;
import io.github.ahrimjang.mail.common.BounceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parser contract over real-shaped SES documents (AWS notification format). */
class SesNotificationParserTest {

    private final SesNotificationParser parser = new SesNotificationParser();

    @Test
    void permanentBounce_mapsEveryRecipientToHardBounceWithCorrelation() {
        String ses = """
                {"notificationType":"Bounce",
                 "bounce":{"bounceType":"Permanent","bounceSubType":"General",
                   "bouncedRecipients":[
                     {"emailAddress":"gone@example.com","diagnosticCode":"smtp; 550 5.1.1 user unknown"},
                     {"emailAddress":"also-gone@example.com"}]},
                 "mail":{"headers":[
                   {"name":"Subject","value":"hello"},
                   {"name":"X-Mail-Message-Id","value":"12345"}]}}
                """;

        List<BounceNotification> out = parser.parse(ses);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).email()).isEqualTo("gone@example.com");
        assertThat(out.get(0).type()).isEqualTo(BounceType.HARD_BOUNCE);
        assertThat(out.get(0).reason()).isEqualTo("smtp; 550 5.1.1 user unknown");
        assertThat(out.get(0).messageId()).isEqualTo(12345L);
        // recipient without a diagnostic code falls back to the bounce subtype
        assertThat(out.get(1).reason()).isEqualTo("General");
        assertThat(out.get(1).messageId()).isEqualTo(12345L);
    }

    @Test
    void transientBounce_mapsToSoftBounce() {
        String ses = """
                {"notificationType":"Bounce",
                 "bounce":{"bounceType":"Transient","bounceSubType":"MailboxFull",
                   "bouncedRecipients":[{"emailAddress":"full@example.com"}]},
                 "mail":{"headers":[]}}
                """;

        List<BounceNotification> out = parser.parse(ses);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo(BounceType.SOFT_BOUNCE);
        assertThat(out.get(0).messageId()).isNull(); // no echoed header — no correlation
    }

    @Test
    void complaint_mapsToComplaintWithFeedbackType() {
        String ses = """
                {"notificationType":"Complaint",
                 "complaint":{"complaintFeedbackType":"abuse",
                   "complainedRecipients":[{"emailAddress":"angry@example.com"}]},
                 "mail":{"headers":[{"name":"x-mail-message-id","value":"77"}]}}
                """;

        List<BounceNotification> out = parser.parse(ses);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo(BounceType.COMPLAINT);
        assertThat(out.get(0).reason()).isEqualTo("abuse");
        assertThat(out.get(0).messageId()).isEqualTo(77L); // header match is case-insensitive
    }

    @Test
    void deliveryAndUnknownTypes_produceNothing() {
        assertThat(parser.parse("{\"notificationType\":\"Delivery\",\"mail\":{}}")).isEmpty();
        assertThat(parser.parse("{\"notificationType\":\"Whatever\"}")).isEmpty();
    }

    @Test
    void nonNumericCorrelationHeader_yieldsNullInsteadOfBlowingUp() {
        String ses = """
                {"notificationType":"Bounce",
                 "bounce":{"bounceType":"Permanent","bouncedRecipients":[{"emailAddress":"x@example.com"}]},
                 "mail":{"headers":[{"name":"X-Mail-Message-Id","value":"not-a-number"}]}}
                """;

        assertThat(parser.parse(ses).get(0).messageId()).isNull();
    }

    @Test
    void garbage_isRejectedLoudly() {
        assertThatThrownBy(() -> parser.parse("this is not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
