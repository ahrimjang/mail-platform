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
    public void send(String recipient, String subject, String body) throws MailSendException {
        if (recipient == null || !recipient.contains("@")) {
            throw new MailSendException("invalid recipient address: " + recipient);
        }
        MimeMessage msg = mailSender.createMimeMessage();
        try {
            MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
            h.setTo(recipient);
            h.setSubject(subject);
            h.setText(body, true);
            mailSender.send(msg);
        } catch (Exception e) {
            throw new MailSendException("failed to send to " + recipient + ": " + e.getMessage(), e);
        }
        log.info("[SMTP] -> {} | subject=\"{}\" | bodyChars={}",
                recipient, subject, body == null ? 0 : body.length());
    }
}
