package io.github.ahrimjang.mail.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailContentValidatorTest {

    private final EmailContentValidator validator = new EmailContentValidator();

    // ── 거부 ─────────────────────────────────────────────────────────────

    @Test
    void rejectsScriptIframeAndForm() {
        for (String tag : List.of("script", "iframe", "form")) {
            assertThatThrownBy(() -> validator.validate("제목", "<p>본문</p><" + tag + ">x</" + tag + ">"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("<" + tag + ">");
        }
    }

    @Test
    void rejectsBlockedTagsRegardlessOfCaseAndAttributes() {
        assertThatThrownBy(() -> validator.validate("제목", "<SCRIPT src=\"x.js\"></SCRIPT>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("제목", "< iframe  width=\"1\">"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotRejectTagsThatMerelyStartWithABlockedName() {
        // <formula>, <scripted> 같은 이름은 막을 이유가 없다 — \b 경계가 이걸 가른다
        assertThatCode(() -> validator.validate("제목", "<formula>x</formula>"))
                .doesNotThrowAnyException();
    }

    // ── 변수 경고 ────────────────────────────────────────────────────────

    @Test
    void warnsOnVariablesThatAreNotBuiltIn() {
        List<String> warnings = validator.validate("{{plan}}님께", "<p>{{couponCode}}</p>");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("{{plan}}").contains("{{couponCode}}").contains("빈칸");
    }

    @Test
    void doesNotWarnOnBuiltInVariables() {
        assertThat(validator.validate("{{name}}님", "<p>{{email}} {{firstName}} {{lastName}}</p>")).isEmpty();
    }

    @Test
    void variableWarningCoversSubjectAndBodyWithoutDuplicates() {
        List<String> warnings = validator.validate("{{dup}}", "<p>{{dup}} {{other}}</p>");

        assertThat(warnings).hasSize(1);
        // 같은 이름이 여러 번 나와도 한 번만 알린다
        assertThat(warnings.get(0).split("\\{\\{dup}}", -1)).hasSize(2);
        assertThat(warnings.get(0)).contains("{{other}}");
    }

    @Test
    void warnsOnKoreanVariableNamesBecauseTheyShipLiterally() {
        // 렌더러는 영문·숫자·밑줄만 치환한다 — {{고객명}} 은 빈칸도 아니고 중괄호째 나간다
        List<String> warnings = validator.validate("{{고객명}}님께", "<p>{{주문 번호}}</p>");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("{{고객명}}")
                .contains("{{주문 번호}}")
                .contains("그대로 발송");
    }

    @Test
    void separatesUnknownVariablesFromMalformedOnes() {
        List<String> warnings = validator.validate("제목", "<p>{{couponCode}} {{쿠폰}}</p>");

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).contains("{{couponCode}}").contains("빈칸");
        assertThat(warnings.get(1)).contains("{{쿠폰}}").contains("그대로 발송");
    }

    @Test
    void ignoresBracesThatAreNotVariableSyntax() {
        // 중괄호 하나짜리는 변수 표기가 아니다 — 여기까지 경고하면 잡음이 된다
        assertThat(validator.validate("제목", "<p>{ single }</p>")).isEmpty();
    }

    @Test
    void treatsSurroundingWhitespaceTheSameWayTheRendererDoes() {
        // {{ spaced }} 는 렌더러가 치환하므로 검사기도 변수로 본다
        assertThat(validator.validate("제목", "<p>{{ spaced }}</p>"))
                .anySatisfy(w -> assertThat(w).contains("{{spaced}}"));
        assertThat(new TemplateRenderer().render("{{ spaced }}", java.util.Map.of("spaced", "값")))
                .isEqualTo("값");
    }

    // ── 크기 경고 ────────────────────────────────────────────────────────

    @Test
    void warnsWhenBodyExceedsGmailClipThreshold() {
        String big = "<p>" + "가".repeat(EmailContentValidator.SIZE_WARN_BYTES) + "</p>";   // 한글 3바이트

        List<String> warnings = validator.validate("제목", big);

        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("Gmail").contains("수신거부"));
    }

    @Test
    void doesNotWarnOnOrdinarySizedBody() {
        assertThat(validator.validate("제목", "<p>" + "x".repeat(1000) + "</p>")).isEmpty();
    }

    // ── 경계 ─────────────────────────────────────────────────────────────

    @Test
    void handlesNulls() {
        assertThatCode(() -> validator.validate(null, null)).doesNotThrowAnyException();
        assertThat(validator.validate(null, null)).isEmpty();
    }
}
