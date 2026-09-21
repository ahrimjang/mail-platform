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
    void acceptsSignaturesFromPreviousKeysAfterRotation() {
        // 교체 전에 발송된 메일의 링크 — 옛 키로 서명돼 있다
        String old = new TrackingLinkSigner("old-secret").sign("tok", "https://example.com/x");

        TrackingLinkSigner rotated = new TrackingLinkSigner("new-secret", "old-secret");
        assertThat(rotated.verify("tok", "https://example.com/x", old)).isTrue();
        // 새 서명은 새 키로 나온다
        assertThat(rotated.sign("tok", "https://example.com/x"))
                .isEqualTo(new TrackingLinkSigner("new-secret").sign("tok", "https://example.com/x"));
    }

    @Test
    void acceptsAnyOfSeveralPreviousKeys() {
        String first = new TrackingLinkSigner("key-1").sign("tok", "https://u");
        String second = new TrackingLinkSigner("key-2").sign("tok", "https://u");

        TrackingLinkSigner rotated = new TrackingLinkSigner("key-3", " key-1 , key-2 ");
        assertThat(rotated.verify("tok", "https://u", first)).isTrue();
        assertThat(rotated.verify("tok", "https://u", second)).isTrue();
    }

    @Test
    void droppingAPreviousKeyRetiresItsSignatures() {
        String old = new TrackingLinkSigner("old-secret").sign("tok", "https://u");
        assertThat(new TrackingLinkSigner("new-secret", "").verify("tok", "https://u", old)).isFalse();
    }

    @Test
    void blankPreviousKeyEntriesAreIgnored() {
        // 후행 쉼표·공백으로 빈 키가 목록에 끼면 HMAC 이 빈 키를 거부해 검증이 통째로 터진다
        TrackingLinkSigner signer = new TrackingLinkSigner("real-secret", ",  ,");
        String sig = signer.sign("tok", "https://u");
        assertThat(signer.verify("tok", "https://u", sig)).isTrue();
        assertThat(signer.verify("tok", "https://u", "not-a-signature")).isFalse();
    }

    @Test
    void rejectsNulls() {
        assertThat(signer.verify(null, "u", "s")).isFalse();
        assertThat(signer.verify("t", null, "s")).isFalse();
        assertThat(signer.verify("t", "u", null)).isFalse();
    }
}
