package io.github.ahrimjang.mail.infra.mail;

import io.github.ahrimjang.mail.core.port.MailSender;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real SMTP mail adapter (JavaMail). Sends the campaign body as HTML to the
 * configured SMTP host (e.g. MailHog in dev). Activated by
 * {@code mail.sender.type=smtp}; otherwise {@link LoggingMailSender} is used.
 */
@Component
@ConditionalOnProperty(name = "mail.sender.type", havingValue = "smtp")
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private final JavaMailSender mailSender;

    public SmtpMailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(String recipient, String subject, String body, String messageId,
                     String senderName, String senderEmail) throws MailSendException {
        if (recipient == null || !recipient.contains("@")) {
            throw new MailSendException("invalid recipient address: " + recipient);
        }
        // Timed against the global registry: rate(count) is the platform's real
        // send throughput, the histogram is SMTP relay latency.
        io.micrometer.core.instrument.Timer.Sample sample =
                io.micrometer.core.instrument.Timer.start(io.micrometer.core.instrument.Metrics.globalRegistry);
        MimeMessage msg = mailSender.createMimeMessage();
        try {
            MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
            h.setTo(recipient);
            h.setSubject(subject);
            h.setText(body, true);
            // Campaign-level From override; without it the SMTP session default applies.
            if (senderEmail != null && !senderEmail.isBlank()) {
                if (senderName != null && !senderName.isBlank()) {
                    h.setFrom(senderEmail, senderName);
                } else {
                    h.setFrom(senderEmail);
                }
            }
            if (messageId != null) {
                msg.setHeader("X-Mail-Message-Id", messageId);
            }
            mailSender.send(msg);
            stop(sample, "ok");
        } catch (Exception e) {
            stop(sample, "error");
            throw new MailSendException("failed to send to " + recipient + ": " + e.getMessage(), e);
        }
        log.info("[SMTP] -> {} | from={} | subject=\"{}\" | bodyChars={}",
                recipient, senderEmail == null ? "(default)" : senderEmail,
                subject, body == null ? 0 : body.length());
    }

    private static void stop(io.micrometer.core.instrument.Timer.Sample sample, String outcome) {
        sample.stop(io.micrometer.core.instrument.Timer.builder("mail.smtp.send")
                .tag("outcome", outcome)
                .register(io.micrometer.core.instrument.Metrics.globalRegistry));
    }
}
