package io.github.ahrimjang.mail.core.service;

import org.junit.jupiter.api.Test;

import static io.github.ahrimjang.mail.core.service.EmailAddressValidator.Verdict;
import static org.assertj.core.api.Assertions.assertThat;

class EmailAddressValidatorTest {

    @Test
    void acceptsRealisticAddresses() {
        assertThat(EmailAddressValidator.check("hong@gmail.com")).isEqualTo(Verdict.OK);
        assertThat(EmailAddressValidator.check("first.last+tag@company.co.kr")).isEqualTo(Verdict.OK);
        assertThat(EmailAddressValidator.check("user_name@sub.domain.io")).isEqualTo(Verdict.OK);
    }

    @Test
    void rejectsMalformedShapes() {
        // 배달 자체가 불가능한 형태들 — 큐에 넣으면 100% 바운스
        assertThat(EmailAddressValidator.check("no-at-sign")).isEqualTo(Verdict.MALFORMED);
        assertThat(EmailAddressValidator.check("two@@at.com")).isEqualTo(Verdict.MALFORMED);
        assertThat(EmailAddressValidator.check("space in@mail.com")).isEqualTo(Verdict.MALFORMED);
        assertThat(EmailAddressValidator.check("no@tld")).isEqualTo(Verdict.MALFORMED);
        assertThat(EmailAddressValidator.check("dots..double@mail.com")).isEqualTo(Verdict.MALFORMED);
        assertThat(EmailAddressValidator.check(".lead@mail.com")).isEqualTo(Verdict.MALFORMED);
        assertThat(EmailAddressValidator.check(null)).isEqualTo(Verdict.MALFORMED);
    }

    @Test
    void rejectsPlaceholderDomains() {
        assertThat(EmailAddressValidator.check("a@test.com")).isEqualTo(Verdict.INVALID_DOMAIN);
        assertThat(EmailAddressValidator.check("b@sample.com")).isEqualTo(Verdict.INVALID_DOMAIN);
        assertThat(EmailAddressValidator.check("c@ASDF.com")).isEqualTo(Verdict.INVALID_DOMAIN);   // 대소문자 무시
        // RFC 2606 예약 도메인은 통과시킨다 — 테스트 관례상 널리 쓰이고, 실발송되면 바운스가 잡는다
        assertThat(EmailAddressValidator.check("d@example.com")).isEqualTo(Verdict.OK);
    }

    @Test
    void flagsTypoDomainsWithSuggestion() {
        assertThat(EmailAddressValidator.check("hong@gmial.com")).isEqualTo(Verdict.LIKELY_TYPO);
        assertThat(EmailAddressValidator.suggestionFor("hong@gmial.com")).isEqualTo("hong@gmail.com");
        assertThat(EmailAddressValidator.check("kim@naver.co")).isEqualTo(Verdict.LIKELY_TYPO);
        assertThat(EmailAddressValidator.suggestionFor("kim@naver.co")).isEqualTo("kim@naver.com");
        assertThat(EmailAddressValidator.suggestionFor("hong@gmail.com")).isNull();   // 정상은 제안 없음
    }

    @Test
    void isSendableOnlyForOk() {
        assertThat(EmailAddressValidator.isSendable("hong@gmail.com")).isTrue();
        assertThat(EmailAddressValidator.isSendable("hong@gmial.com")).isFalse();
        assertThat(EmailAddressValidator.isSendable("a@test.com")).isFalse();
    }
}
