package io.github.ahrimjang.mail.core.port;

/**
 * Outbound port for actually transmitting a single mail.
 *
 * <p>The POC ships a logging adapter; a real SMTP/JavaMail or provider-API
 * adapter is a drop-in replacement that implements this same interface.
 */
public interface MailSender {

    /**
     * 캠페인 발송에만 붙는 부가 정보. 가입 인증·비밀번호 재설정 같은 트랜잭셔널 메일은
     * 해당이 없어 {@link #NONE} 을 쓴다. 파라미터를 계속 늘리는 대신 여기 모은다.
     *
     * @param replyTo            회신 주소; null 이면 미설정 — 발신은 서비스 도메인으로 고정하고
     *                           수신자의 답장만 고객 주소로 보내는 SES 구조의 짝
     * @param listUnsubscribeUrl 원클릭 수신거부 URL(RFC 8058); null 이면 헤더를 붙이지 않는다
     */
    record Options(String replyTo, String listUnsubscribeUrl) {

        public static final Options NONE = new Options(null, null);
    }

    /**
     * Send one mail.
     *
     * @param senderName  From display name; null falls back to the adapter default
     * @param senderEmail From address; null falls back to the adapter default
     * @throws MailSendException if delivery fails (the worker records it as FAILED)
     */
    void send(String recipient, String subject, String body, String messageId,
              String senderName, String senderEmail, Options options) throws MailSendException;

    /** 부가 정보가 없는 호출 경로(트랜잭셔널·인증 메일). */
    default void send(String recipient, String subject, String body, String messageId,
                      String senderName, String senderEmail) throws MailSendException {
        send(recipient, subject, body, messageId, senderName, senderEmail, Options.NONE);
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
