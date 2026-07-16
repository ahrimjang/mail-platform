package io.github.ahrimjang.mail.api.webhook;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature contract exercised with a locally generated RSA key pair: the
 * verifier receives our public key through its provider seam and must accept
 * exactly the payloads signed with the matching private key.
 */
class SnsSignatureVerifierTest {

    private static final String CERT_URL = "https://sns.ap-northeast-2.amazonaws.com/SimpleNotificationService-abc.pem";

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    private SnsSignatureVerifier verifier() {
        return new SnsSignatureVerifier(url -> keyPair.getPublic());
    }

    private static SnsMessage notification(String message, String signatureVersion, String certUrl) throws Exception {
        SnsMessage unsigned = new SnsMessage(
                "Notification", "msg-1", "arn:aws:sns:ap-northeast-2:123:mail-events", null,
                message, "2026-07-16T12:00:00.000Z", null, null,
                signatureVersion, null, certUrl);
        Signature signer = Signature.getInstance("2".equals(signatureVersion) ? "SHA256withRSA" : "SHA1withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(unsigned.stringToSign().getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        return new SnsMessage(
                unsigned.type(), unsigned.messageId(), unsigned.topicArn(), unsigned.subject(),
                unsigned.message(), unsigned.timestamp(), unsigned.token(), unsigned.subscribeUrl(),
                signatureVersion, signature, certUrl);
    }

    @Test
    void acceptsAProperlySignedNotification_v1AndV2() throws Exception {
        assertThat(verifier().isValid(notification("{\"a\":1}", "1", CERT_URL))).isTrue();
        assertThat(verifier().isValid(notification("{\"a\":1}", "2", CERT_URL))).isTrue();
    }

    @Test
    void rejectsATamperedMessage() throws Exception {
        SnsMessage signed = notification("{\"a\":1}", "1", CERT_URL);
        SnsMessage tampered = new SnsMessage(
                signed.type(), signed.messageId(), signed.topicArn(), signed.subject(),
                "{\"a\":2}", // body swapped after signing
                signed.timestamp(), signed.token(), signed.subscribeUrl(),
                signed.signatureVersion(), signed.signature(), signed.signingCertUrl());

        assertThat(verifier().isValid(tampered)).isFalse();
    }

    @Test
    void rejectsACertUrlOutsideAmazon_beforeFetchingAnything() throws Exception {
        // The provider must never even be consulted for a foreign cert URL.
        SnsSignatureVerifier verifier = new SnsSignatureVerifier(url -> {
            throw new AssertionError("cert fetch attempted for untrusted url");
        });

        assertThat(verifier.isValid(notification("{}", "1", "https://evil.example.com/cert.pem"))).isFalse();
        assertThat(verifier.isValid(notification("{}", "1", "http://sns.amazonaws.com/cert.pem"))).isFalse();
    }

    @Test
    void certUrlValidation_coversTheEdges() {
        assertThat(SnsSignatureVerifier.isAmazonCertUrl("https://sns.us-east-1.amazonaws.com/x.pem")).isTrue();
        assertThat(SnsSignatureVerifier.isAmazonCertUrl("https://sns.amazonaws.com.evil.com/x.pem")).isFalse();
        assertThat(SnsSignatureVerifier.isAmazonCertUrl("http://sns.amazonaws.com/x.pem")).isFalse();
        assertThat(SnsSignatureVerifier.isAmazonCertUrl(null)).isFalse();
    }
}
