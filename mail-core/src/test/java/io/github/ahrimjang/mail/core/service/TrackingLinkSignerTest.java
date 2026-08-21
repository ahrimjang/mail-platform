package io.github.ahrimjang.mail.core.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingLinkSignerTest {

    private final TrackingLinkSigner signer = new TrackingLinkSigner("a-secret");

    @Test
    void verifiesOwnSignature() {
        String sig = signer.sign("tok", "https://example.com/x");
        assertThat(signer.verify("tok", "https://example.com/x", sig)).isTrue();
    }

    @Test
    void signatureIsBoundToTokenAndUrl() {
        String sig = signer.sign("tok", "https://example.com/x");
        assertThat(signer.verify("tok", "https://example.com/OTHER", sig)).isFalse();  // URL 바뀜
        assertThat(signer.verify("OTHER", "https://example.com/x", sig)).isFalse();     // 토큰 바뀜
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        String a = new TrackingLinkSigner("secret-a").sign("t", "https://u");
        String b = new TrackingLinkSigner("secret-b").sign("t", "https://u");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void rejectsNulls() {
        assertThat(signer.verify(null, "u", "s")).isFalse();
        assertThat(signer.verify("t", null, "s")).isFalse();
        assertThat(signer.verify("t", "u", null)).isFalse();
    }
}
