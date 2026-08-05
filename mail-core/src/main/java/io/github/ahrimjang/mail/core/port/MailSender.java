package io.github.ahrimjang.mail.core.port;

/**
 * Outbound port for actually transmitting a single mail.
 *
 * <p>The POC ships a logging adapter; a real SMTP/JavaMail or provider-API
 * adapter is a drop-in replacement that implements this same interface.
 */
public interface MailSender {

    /**
     * Send one mail.
     *
     * @param senderName  From display name; null falls back to the adapter default
     * @param senderEmail From address; null falls back to the adapter default
     * @param replyTo     회신 주소; null 이면 미설정 — 발신은 서비스 도메인으로 고정하고
     *                    수신자의 답장만 고객 주소로 보내는 SES 구조의 짝
     * @throws MailSendException if delivery fails (the worker records it as FAILED)
     */
    void send(String recipient, String subject, String body, String messageId,
              String senderName, String senderEmail, String replyTo) throws MailSendException;

    /** Reply-To 없는 기존 호출 경로 호환. */
    default void send(String recipient, String subject, String body, String messageId,
                      String senderName, String senderEmail) throws MailSendException {
        send(recipient, subject, body, messageId, senderName, senderEmail, null);
    }

    /** Thrown when a single delivery attempt fails. */
    class MailSendException extends RuntimeException {
        public MailSendException(String message) {
            super(message);
        }

        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
