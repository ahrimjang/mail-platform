package io.github.ahrimjang.mail.core.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SenderPolicyTest {

    @Test
    void unsetDomain_allowsAnySender() {
        SenderPolicy policy = new SenderPolicy("");   // 개발 기본 — 제한 없음

        assertThatCode(() -> policy.assertSenderAllowed("anyone@their-company.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void configuredDomain_allowsOnlyThatDomain() {
        SenderPolicy policy = new SenderPolicy("outpacemail.com");

        assertThatCode(() -> policy.assertSenderAllowed("news@outpacemail.com")).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertSenderAllowed("NEWS@OUTPACEMAIL.COM")).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertSenderAllowed(null)).doesNotThrowAnyException();   // 기본 발신자
        assertThatCode(() -> policy.assertSenderAllowed("  ")).doesNotThrowAnyException();

        assertThatThrownBy(() -> policy.assertSenderAllowed("hong@their-company.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outpacemail.com");
        // 서브도메인 위장(evil-outpacemail.com)도 @도메인 정확 일치라 걸린다
        assertThatThrownBy(() -> policy.assertSenderAllowed("a@evil-outpacemail.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replyTo_onlyChecksShape() {
        SenderPolicy policy = new SenderPolicy("outpacemail.com");

        assertThatCode(() -> policy.assertReplyToValid("hong@their-company.com"))
                .doesNotThrowAnyException();   // 회신 주소는 도메인 제한 없음
        assertThatCode(() -> policy.assertReplyToValid(null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertReplyToValid("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
