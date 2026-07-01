package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.core.service.SuppressionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public unsubscribe endpoint: resolves the per-message token, suppresses the
 * recipient's address, and returns a simple confirmation page.
 */
@RestController
public class UnsubscribeController {

    private final SuppressionService suppressions;

    public UnsubscribeController(SuppressionService suppressions) {
        this.suppressions = suppressions;
    }

    @GetMapping(value = "/api/unsubscribe/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String unsubscribe(@PathVariable String token) {
        suppressions.suppressByUnsubToken(token);
        return "<html><body style=\"font-family:system-ui;text-align:center;padding:3rem\">" +
                "<h2>수신거부 완료</h2><p>더 이상 이 메일을 받지 않습니다.</p></body></html>";
    }
}
