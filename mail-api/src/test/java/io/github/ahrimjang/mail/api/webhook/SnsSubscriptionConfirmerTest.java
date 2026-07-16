package io.github.ahrimjang.mail.api.webhook;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SnsSubscriptionConfirmerTest {

    @Test
    void confirmsByFetchingAnAmazonSubscribeUrl() {
        AtomicReference<String> fetched = new AtomicReference<>();
        SnsSubscriptionConfirmer confirmer = new SnsSubscriptionConfirmer(url -> {
            fetched.set(url);
            return 200;
        });

        boolean ok = confirmer.confirm(
                "https://sns.ap-northeast-2.amazonaws.com/?Action=ConfirmSubscription&Token=abc");

        assertThat(ok).isTrue();
        assertThat(fetched.get()).contains("ConfirmSubscription");
    }

    @Test
    void refusesToFetchANonAmazonUrl() {
        // A forged SubscriptionConfirmation must not turn this endpoint into an SSRF.
        SnsSubscriptionConfirmer confirmer = new SnsSubscriptionConfirmer(url -> {
            throw new AssertionError("fetched an untrusted url");
        });

        assertThat(confirmer.confirm("https://attacker.example.com/steal")).isFalse();
        assertThat(confirmer.confirm(null)).isFalse();
    }

    @Test
    void aFailedFetchReportsFalseInsteadOfThrowing() {
        SnsSubscriptionConfirmer confirmer = new SnsSubscriptionConfirmer(url -> 500);

        assertThat(confirmer.confirm("https://sns.amazonaws.com/confirm")).isFalse();
    }
}
