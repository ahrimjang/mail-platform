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

    /** Only HTTPS URLs on an amazonaws.com host may supply the certificate. */
    static boolean isAmazonCertUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme())
                    && host != null
                    && (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn"));
        } catch (Exception e) {
            return false;
        }
    }

    private static PublicKey fetchAmazonCertKey(String certUrl) throws Exception {
        try (InputStream in = URI.create(certUrl).toURL().openStream()) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            return cert.getPublicKey();
        }
    }
}
