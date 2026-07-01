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
     * @throws MailSendException if delivery fails (the worker records it as FAILED)
     */
    void send(String recipient, String subject, String body) throws MailSendException;

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
