package io.github.ahrimjang.mail.infra.mail;

import io.github.ahrimjang.mail.core.port.MailSender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 전선에 실리는 MIME 헤더를 직접 본다. 코어 테스트는 "URL 을 어댑터에 넘겼다"까지만
 * 보장하고, 그 URL 이 실제 헤더가 되는지는 여기서만 확인된다 — 코드는 맞았는데 배선이
 * 빠져 무력했던 전례(2026-09-02)가 있어 두 층을 따로 잠근다.
 */
class SmtpMailSenderTest {

    private final JavaMailSender javaMail = mock(JavaMailSender.class);
    private final ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
    private SmtpMailSender sender;

    @BeforeEach
    void setUp() {
        // 실제 MimeMessage 를 돌려주고, 전송은 붙잡기만 한다(서버 없음)
        when(javaMail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        sender = new SmtpMailSender(javaMail);
    }

    @Test
    void campaignMail_carriesOneClickUnsubscribeHeaders() throws Exception {
        String url = "https://outpacemail.com/api/unsubscribe/0b3a2f8e-7d4c-4c1e-9a2b-5f6e7d8c9a01";

        sender.send("to@corp.example", "제목", "<p>본문</p>", "42",
                "Acme", "news@outpacemail.com", new MailSender.Options("team@acme.io", url));

        verify(javaMail).send(sent.capture());
        MimeMessage msg = sent.getValue();
        // RFC 8058: URL 은 꺾쇠로 감싸고, One-Click 헤더가 짝으로 있어야 앱이 확인 없이 POST 한다
        assertThat(msg.getHeader("List-Unsubscribe")).containsExactly("<" + url + ">");
        assertThat(msg.getHeader("List-Unsubscribe-Post")).containsExactly("List-Unsubscribe=One-Click");
        assertThat(msg.getHeader("X-Mail-Message-Id")).containsExactly("42");
        assertThat(msg.getReplyTo()[0].toString()).isEqualTo("team@acme.io");
    }

    @Test
    void transactionalMail_hasNoUnsubscribeHeaders() throws Exception {
        // 가입 인증·비밀번호 재설정은 구독이 아니다 — 수신거부 버튼이 뜨면 안 된다
        sender.send("to@corp.example", "인증", "<p>링크</p>", null, "Outpace", null);

        verify(javaMail).send(sent.capture());
        MimeMessage msg = sent.getValue();
        assertThat(msg.getHeader("List-Unsubscribe")).isNull();
        assertThat(msg.getHeader("List-Unsubscribe-Post")).isNull();
        assertThat(msg.getReplyTo()).isNull();
    }

    @Test
    void rejectsRecipientWithoutAtSign() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sender.send("not-an-address", "s", "b", null, null, null))
                .isInstanceOf(MailSender.MailSendException.class);
        verify(javaMail, org.mockito.Mockito.never()).send(any(MimeMessage.class));
    }
}
