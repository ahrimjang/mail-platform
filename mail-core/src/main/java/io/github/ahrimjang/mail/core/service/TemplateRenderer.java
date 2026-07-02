package io.github.ahrimjang.mail.core.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text-substitution logic for template personalization: replaces
 * {@code {{variable}}} placeholders with values from a variable map.
 * Unknown variables render as the empty string.
 */
@Component
public class TemplateRenderer {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}");

    /**
     * Replace every {@code {{name}}} placeholder in {@code text} with the
     * matching value from {@code vars}; missing or null values become "".
     */
    public String render(String text, Map<String, String> vars) {
        if (text == null) {
            return "";
        }
        Matcher matcher = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String v = vars.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
