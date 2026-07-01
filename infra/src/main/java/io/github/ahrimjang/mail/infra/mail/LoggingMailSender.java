package io.github.ahrimjang.mail.infra.mail;

import io.github.ahrimjang.mail.core.port.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * POC mail adapter: logs each "delivery" instead of contacting a real MTA, so
 * the whole pipeline runs with zero external dependencies.
 *
 * <p>Addresses without an '@' are rejected to exercise the FAILED path. Swap
 * this bean for a JavaMail/provider-API implementation of {@link MailSender}
 * to send for real.
 */
@Component
@ConditionalOnProperty(name = "mail.sender.type", havingValue = "logging", matchIfMissing = true)
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(String recipient, String subject, String body, String messageId) throws MailSendException {
        if (recipient == null || !recipient.contains("@")) {
            throw new MailSendException("invalid recipient address: " + recipient);
        }
        log.info("[MAIL] -> {} | subject=\"{}\" | bodyChars={} | messageId={}",
                recipient, subject, body == null ? 0 : body.length(), messageId);
    }
}
