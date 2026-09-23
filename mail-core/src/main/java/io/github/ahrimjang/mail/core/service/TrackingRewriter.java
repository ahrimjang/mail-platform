package io.github.ahrimjang.mail.core.service;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure HTML-rewriting logic for engagement tracking: turns outbound links into
 * click-tracking redirects and produces the 1x1 open-tracking pixel.
 */
@Component
public class TrackingRewriter {

    private static final Pattern HREF = Pattern.compile("href=\"(https?://[^\"]+)\"");
    /** 닫는 body 태그 — 대소문자·공백 변형(`</BODY >`)까지 받는다. */
    private static final Pattern BODY_END = Pattern.compile("</\\s*body\\s*>", Pattern.CASE_INSENSITIVE);

    private final TrackingLinkSigner signer;

    public TrackingRewriter(TrackingLinkSigner signer) {
        this.signer = signer;
    }

    /**
     * Rewrite every {@code href} pointing at an http(s) URL to route through the
     * click-tracking endpoint. Non-http hrefs are left untouched. 목적지 URL 에는
     * HMAC 서명(s)을 붙여, 리다이렉트 엔드포인트가 우리가 발행한 링크만 통과시키게 한다.
     */
    public String rewriteLinks(String html, String trackingToken, String baseUrl) {
        Matcher matcher = HREF.matcher(html);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1);
            String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
            String sig = signer.sign(trackingToken, url);
            String replacement = "href=\"" + baseUrl + "/api/track/click/" + trackingToken
                    + "?u=" + encoded + "&s=" + sig + "\"";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 수신거부 푸터·오픈 픽셀을 본문에 덧붙인다. 완성형 HTML 문서면 <b>{@code </body>} 앞</b>에
     * 넣는다.
     *
     * <p>그냥 뒤에 이어 붙이면 {@code </html>} 바깥에 놓인다. 메일 클라이언트마다 문서 밖
     * 내용을 어떻게 다루는지가 달라서, 수신거부 링크가 통째로 버려질 수 있다 — 그러면 법적
     * 요건(수신거부 수단 제공)이 깨지고 스팸 신고로 이어진다. 조각 HTML(닫는 태그가 없는
     * 본문)은 붙일 자리가 없으니 지금처럼 뒤에 잇는다.
     */
    public String appendInsideBody(String html, String extra) {
        if (html == null || html.isBlank()) {
            return extra;
        }
        Matcher matcher = BODY_END.matcher(html);
        int insertAt = -1;
        // 중첩·따옴표 안의 가짜 태그를 피하려는 게 아니라, 진짜 문서 끝을 잡으려는 것 —
        // 마지막 </body> 앞이 항상 문서 본문의 끝이다.
        while (matcher.find()) {
            insertAt = matcher.start();
        }
        return insertAt < 0 ? html + extra : html.substring(0, insertAt) + extra + html.substring(insertAt);
    }

    /** Build the hidden 1x1 open-tracking pixel for the given token. */
    public String openPixel(String trackingToken, String baseUrl) {
        return "<img src=\"" + baseUrl + "/api/track/open/" + trackingToken +
                "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none\"/>";
    }
}
