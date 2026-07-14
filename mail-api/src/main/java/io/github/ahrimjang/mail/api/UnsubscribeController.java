package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.core.service.SuppressionService;
import io.github.ahrimjang.mail.core.service.SuppressionService.UnsubscribeContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Public unsubscribe pages. The footer link lands on a choice page: a recipient
 * of a list campaign can leave just that list (membership removal — other lists
 * and transactional mail keep flowing) or stop all mail (global suppression).
 * Ad-hoc/transactional recipients only get the global opt-out.
 */
@RestController
public class UnsubscribeController {

    private final SuppressionService suppressions;

    public UnsubscribeController(SuppressionService suppressions) {
        this.suppressions = suppressions;
    }

    /** Choice page — nothing is unsubscribed until one of the POSTs below. */
    @GetMapping(value = "/api/unsubscribe/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String choose(@PathVariable String token) {
        Optional<UnsubscribeContext> ctx = suppressions.unsubscribeContext(token);
        if (ctx.isEmpty()) {
            return page("잘못된 링크", "<p>이미 처리되었거나 유효하지 않은 수신거부 링크입니다.</p>");
        }
        UnsubscribeContext c = ctx.get();
        StringBuilder body = new StringBuilder();
        body.append("<p>").append(escape(c.recipient())).append(" 님, 어떤 메일을 그만 받을까요?</p>");
        if (c.canUnsubscribeFromList()) {
            body.append(form(token, "list",
                    "‘" + escape(c.listName()) + "’ 리스트만 그만 받기",
                    "이 리스트의 캠페인만 중단됩니다. 다른 메일은 계속 받습니다."));
        }
        body.append(form(token, "all",
                "모든 메일 수신거부",
                "이 주소로 보내는 모든 메일이 중단됩니다."));
        return page("수신거부", body.toString());
    }

    /** Leave only the campaign's list; the global suppression list is untouched. */
    @PostMapping(value = "/api/unsubscribe/{token}/list", produces = MediaType.TEXT_HTML_VALUE)
    public String unsubscribeList(@PathVariable String token) {
        return suppressions.unsubscribeFromList(token)
                .map(c -> page("리스트 구독 해지 완료",
                        "<p>‘" + escape(c.listName()) + "’ 리스트에서 해지되었습니다.<br>다른 메일은 계속 받습니다.</p>"))
                .orElseGet(() -> page("처리할 수 없습니다",
                        "<p>이 메일은 리스트 단위로 해지할 수 없습니다. 아래에서 전체 수신거부를 이용해 주세요.</p>"
                                + form(token, "all", "모든 메일 수신거부", null)));
    }

    /** Stop everything for this address (the original global opt-out). */
    @PostMapping(value = "/api/unsubscribe/{token}/all", produces = MediaType.TEXT_HTML_VALUE)
    public String unsubscribeAll(@PathVariable String token) {
        suppressions.suppressByUnsubToken(token);
        return page("수신거부 완료", "<p>더 이상 이 메일을 받지 않습니다.</p>");
    }

    private static String form(String token, String action, String label, String help) {
        return "<form method=\"post\" action=\"/api/unsubscribe/" + token + "/" + action + "\" style=\"margin:1rem 0\">"
                + "<button type=\"submit\" style=\"padding:0.7rem 1.4rem;border-radius:10px;border:1px solid #d4d4d8;"
                + ("all".equals(action) ? "background:#dc2626;color:#fff;border-color:#dc2626;" : "background:#fff;color:#18181b;")
                + "font-size:15px;cursor:pointer\">" + label + "</button>"
                + (help != null ? "<div style=\"font-size:12.5px;color:#71717a;margin-top:0.4rem\">" + help + "</div>" : "")
                + "</form>";
    }

    private static String page(String title, String body) {
        return "<html><body style=\"font-family:system-ui;text-align:center;padding:3rem\">"
                + "<h2>" + title + "</h2>" + body + "</body></html>";
    }

    /** Minimal HTML escaping for user-originated values (list names, addresses). */
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
