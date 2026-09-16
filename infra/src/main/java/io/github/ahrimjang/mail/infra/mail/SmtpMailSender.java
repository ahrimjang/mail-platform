package io.github.ahrimjang.mail.infra.mail;

import io.github.ahrimjang.mail.core.port.MailSender;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final String systemFrom;

    public SmtpMailSender(JavaMailSender mailSender,
                          @Value("${app.mail.system-from:no-reply@outpacemail.com}") String systemFrom) {
        this.mailSender = mailSender;
        this.systemFrom = systemFrom;
    }

    @Override
    public void send(String recipient, String subject, String body, String messageId,
                     String senderName, String senderEmail, Options options) throws MailSendException {
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
            // From 은 언제나 채운다 — 비워 두면 JavaMail 이 헤더를 아예 넣지 않고,
            // SES 는 From 없는 메일을 거부한다(개발 MailHog 는 받아 줘서 안 드러난다).
            // 캠페인은 자기 발신 주소로, 그게 없는 발송(가입 인증·재설정 등 시스템 메일)은
            // app.mail.system-from 으로 — 이 주소도 SES 검증 도메인이어야 한다.
            String from = senderEmail != null && !senderEmail.isBlank() ? senderEmail : systemFrom;
            if (senderName != null && !senderName.isBlank()) {
                h.setFrom(from, senderName);
            } else {
                h.setFrom(from);
            }
            // 발신은 서비스 도메인, 답장은 고객 주소로 — SES 검증 도메인 제약의 짝
            String replyTo = options == null ? null : options.replyTo();
            if (replyTo != null && !replyTo.isBlank()) {
                h.setReplyTo(replyTo);
            }
            if (messageId != null) {
                msg.setHeader("X-Mail-Message-Id", messageId);
            }
            // 원클릭 수신거부(RFC 8058). Gmail·야후는 대량 발송자에게 이 헤더를 요구하고,
            // 없으면 수신자가 "수신거부" 대신 "스팸 신고"를 눌러 컴플레인율이 오른다 —
            // 그 비율은 SES 계정 전체(공유 IP)의 평판에 그대로 꽂힌다.
            String unsubscribeUrl = options == null ? null : options.listUnsubscribeUrl();
            if (unsubscribeUrl != null && !unsubscribeUrl.isBlank()) {
                msg.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");
                // 이 헤더가 있어야 메일 앱이 확인 화면 없이 POST 한 번으로 처리한다.
                msg.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
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
