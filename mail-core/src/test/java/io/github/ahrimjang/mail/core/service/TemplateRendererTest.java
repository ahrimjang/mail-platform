package io.github.ahrimjang.mail.core.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void render_replacesSingleVariable() {
        String out = renderer.render("Hello {{firstName}}!", Map.of("firstName", "Ahrim"));

        assertThat(out).isEqualTo("Hello Ahrim!");
    }

    @Test
    void render_replacesMultipleOccurrencesOfSameVariable() {
        String out = renderer.render("{{name}} and {{name}} again", Map.of("name", "Kim"));

        assertThat(out).isEqualTo("Kim and Kim again");
    }

    @Test
    void render_replacesMultipleDistinctVariables() {
        String out = renderer.render("{{greeting}}, {{firstName}} {{lastName}}",
                Map.of("greeting", "Hi", "firstName", "Ahrim", "lastName", "Jang"));

        assertThat(out).isEqualTo("Hi, Ahrim Jang");
    }

    @Test
    void render_acceptsWhitespaceInsidePlaceholder() {
        String out = renderer.render("Hello {{ firstName }}!", Map.of("firstName", "Ahrim"));

        assertThat(out).isEqualTo("Hello Ahrim!");
    }

    @Test
    void render_unknownVariableBecomesEmptyString() {
        String out = renderer.render("Hello {{missing}}!", Map.of("firstName", "Ahrim"));

        assertThat(out).isEqualTo("Hello !");
    }

    @Test
    void render_nullTextReturnsEmptyString() {
        String out = renderer.render(null, Map.of("firstName", "Ahrim"));

        assertThat(out).isEmpty();
    }

    @Test
    void render_textWithoutVariablesIsUnchanged() {
        String out = renderer.render("Plain text, no placeholders.", Map.of("firstName", "Ahrim"));

        assertThat(out).isEqualTo("Plain text, no placeholders.");
    }

    @Test
    void render_valueContainingDollarAndBackslashIsInsertedLiterally() {
        // Matcher.quoteReplacement must protect regex replacement metacharacters.
        String out = renderer.render("Price: {{price}}", Map.of("price", "$100 \\ off"));

        assertThat(out).isEqualTo("Price: $100 \\ off");
    }
}
