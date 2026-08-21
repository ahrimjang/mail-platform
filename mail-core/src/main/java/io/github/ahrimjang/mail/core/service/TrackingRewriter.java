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

    /** Build the hidden 1x1 open-tracking pixel for the given token. */
    public String openPixel(String trackingToken, String baseUrl) {
        return "<img src=\"" + baseUrl + "/api/track/open/" + trackingToken +
                "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none\"/>";
    }
}
