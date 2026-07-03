package io.github.ahrimjang.mail.core.service;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingRewriterTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "tok-123";

    private final TrackingRewriter rewriter = new TrackingRewriter();

    @Test
    void rewriteLinks_routesHttpsLinkThroughClickEndpointWithEncodedUrl() {
        String url = "https://example.com/page?a=1&b=2";
        String html = "<a href=\"" + url + "\">go</a>";

        String out = rewriter.rewriteLinks(html, TOKEN, BASE_URL);

        String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
        assertThat(out).isEqualTo(
                "<a href=\"" + BASE_URL + "/api/track/click/" + TOKEN + "?u=" + encoded + "\">go</a>");
    }

    @Test
    void rewriteLinks_rewritesEveryHttpLink() {
        String html = "<a href=\"https://a.example\">a</a> <a href=\"http://b.example\">b</a>";

        String out = rewriter.rewriteLinks(html, TOKEN, BASE_URL);

        assertThat(out)
                .doesNotContain("href=\"https://a.example\"")
                .doesNotContain("href=\"http://b.example\"")
                .contains("?u=" + URLEncoder.encode("https://a.example", StandardCharsets.UTF_8))
                .contains("?u=" + URLEncoder.encode("http://b.example", StandardCharsets.UTF_8));
        assertThat(out.split("/api/track/click/" + TOKEN, -1)).hasSize(3); // two rewritten links
    }

    @Test
    void rewriteLinks_leavesNonHttpHrefsUntouched() {
        String html = "<a href=\"mailto:me@example.com\">mail</a> <a href=\"#top\">top</a>";

        String out = rewriter.rewriteLinks(html, TOKEN, BASE_URL);

        assertThat(out).isEqualTo(html);
    }

    @Test
    void rewriteLinks_leavesHtmlWithoutLinksUnchanged() {
        String html = "<p>no links here</p>";

        assertThat(rewriter.rewriteLinks(html, TOKEN, BASE_URL)).isEqualTo(html);
    }

    @Test
    void openPixel_pointsImgAtOpenTrackingEndpoint() {
        String pixel = rewriter.openPixel(TOKEN, BASE_URL);

        assertThat(pixel)
                .startsWith("<img ")
                .contains("src=\"" + BASE_URL + "/api/track/open/" + TOKEN + "\"");
    }
}
