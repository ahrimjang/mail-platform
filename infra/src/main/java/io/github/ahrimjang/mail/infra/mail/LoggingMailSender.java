package io.github.ahrimjang.mail.infra.mail;

import io.github.ahrimjang.mail.core.port.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로그 발송 어댑터 — 실제 메일 서버에 연결하지 않고 "발송"을 로그로만 남겨,
 * 외부 의존성 없이 파이프라인 전체를 돌릴 수 있게 한다(MAIL_SENDER_TYPE=logging).
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
    public void send(String recipient, String subject, String body, String messageId,
                     String senderName, String senderEmail, Options options) throws MailSendException {
        if (recipient == null || !recipient.contains("@")) {
            throw new MailSendException("invalid recipient address: " + recipient);
        }
        log.info("[MAIL] -> {} | from={} <{}> | subject=\"{}\" | bodyChars={} | messageId={}",
                recipient,
                senderName == null ? "(default)" : senderName,
                senderEmail == null ? "default" : senderEmail,
                subject, body == null ? 0 : body.length(), messageId);
    }
}
