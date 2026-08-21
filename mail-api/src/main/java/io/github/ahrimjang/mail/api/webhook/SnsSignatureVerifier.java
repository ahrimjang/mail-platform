package io.github.ahrimjang.mail.api.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies that an SNS delivery was really signed by Amazon — the security
 * layer of a webhook that must stay publicly reachable. The envelope carries
 * a signature over a canonical string ({@link SnsMessage#stringToSign()}) and
 * a URL to the signing certificate; the URL itself is validated (HTTPS on an
 * amazonaws.com host) before anything is fetched, so an attacker cannot point
 * us at their own certificate.
 */
@Component
public class SnsSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SnsSignatureVerifier.class);

    /** Seam for tests: resolves a signing-cert URL to its public key. */
    public interface PublicKeyProvider {
        PublicKey publicKeyFor(String certUrl) throws Exception;
    }

    private final PublicKeyProvider keyProvider;
    private final Map<String, PublicKey> cache = new ConcurrentHashMap<>();

    public SnsSignatureVerifier() {
        this(SnsSignatureVerifier::fetchAmazonCertKey);
    }

    public SnsSignatureVerifier(PublicKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /** True when the message's signature checks out against Amazon's certificate. */
    public boolean isValid(SnsMessage message) {
        try {
            if (!isAmazonCertUrl(message.signingCertUrl())) {
                log.warn("sns signature rejected: untrusted cert url {}", message.signingCertUrl());
                return false;
            }
            PublicKey key = cache.computeIfAbsent(message.signingCertUrl(), url -> {
                try {
                    return keyProvider.publicKeyFor(url);
                } catch (Exception e) {
                    throw new IllegalStateException("failed to load SNS signing certificate", e);
                }
            });
            // SignatureVersion 1 = SHA1withRSA (the long-standing default), 2 = SHA256withRSA.
            String algorithm = "2".equals(message.signatureVersion()) ? "SHA256withRSA" : "SHA1withRSA";
            Signature signature = Signature.getInstance(algorithm);
            signature.initVerify(key);
            signature.update(message.stringToSign().getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(message.signature()));
        } catch (Exception e) {
            log.warn("sns signature verification failed", e);
            return false;
        }
    }

    // SNS 는 서명 인증서도 SubscribeURL 도 오직 sns.<region>.amazonaws.com(.cn) 에서만
    // 제공한다. ".amazonaws.com 으로 끝나면 허용"은 공격자의 S3 버킷(evil.s3.amazonaws.com)에
    // 올린 자기 인증서까지 통과시켜 서명 검증을 무력화한다(AUDIT SEC-2). 호스트를 SNS 전용으로
    // 좁히면, 그 호스트는 AWS 만 TLS 로 서빙하므로 공격자가 콘텐츠를 놓을 수 없다.
    private static final java.util.regex.Pattern SNS_HOST =
            java.util.regex.Pattern.compile("^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$");

    /** HTTPS URL on an SNS host — SubscribeURL 검증용(.pem 요건 없음). */
    static boolean isSnsUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && SNS_HOST.matcher(host).matches();
        } catch (Exception e) {
            return false;
        }
    }

    /** SNS 호스트가 .pem 으로 제공하는 서명 인증서 URL 만 허용. */
    static boolean isAmazonCertUrl(String url) {
        if (!isSnsUrl(url)) {
            return false;
        }
        String path = URI.create(url).getPath();
        return path != null && path.endsWith(".pem");
    }

    private static PublicKey fetchAmazonCertKey(String certUrl) throws Exception {
        try (InputStream in = URI.create(certUrl).toURL().openStream()) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            return cert.getPublicKey();
        }
    }
}
