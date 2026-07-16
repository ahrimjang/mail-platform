package io.github.ahrimjang.mail.api.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Completes the SNS handshake: when a topic is first pointed at this webhook,
 * SNS posts a {@code SubscriptionConfirmation} carrying a {@code SubscribeURL};
 * fetching that URL activates the subscription. Only HTTPS amazonaws.com URLs
 * are followed — the message is attacker-suppliable, the fetch must not be an
 * open SSRF.
 */
@Component
public class SnsSubscriptionConfirmer {

    private static final Logger log = LoggerFactory.getLogger(SnsSubscriptionConfirmer.class);

    /** Seam for tests: performs the confirmation GET. */
    public interface UrlOpener {
        int get(String url) throws Exception;
    }

    private final UrlOpener opener;

    public SnsSubscriptionConfirmer() {
        this(SnsSubscriptionConfirmer::httpGet);
    }

    public SnsSubscriptionConfirmer(UrlOpener opener) {
        this.opener = opener;
    }

    /** Follow the SubscribeURL; returns true when the subscription was confirmed. */
    public boolean confirm(String subscribeUrl) {
        if (!SnsSignatureVerifier.isAmazonCertUrl(subscribeUrl)) {
            log.warn("sns subscription rejected: untrusted subscribe url {}", subscribeUrl);
            return false;
        }
        try {
            int status = opener.get(subscribeUrl);
            log.info("sns subscription confirmed: HTTP {}", status);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            log.warn("sns subscription confirmation failed", e);
            return false;
        }
    }

    private static int httpGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
