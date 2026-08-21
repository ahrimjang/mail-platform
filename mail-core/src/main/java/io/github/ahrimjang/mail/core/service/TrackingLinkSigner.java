package io.github.ahrimjang.mail.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 클릭 추적 링크의 목적지 URL 을 서명·검증한다.
 *
 * <p>클릭 리다이렉트는 공개 엔드포인트라, 서명이 없으면 누구나
 * {@code /api/track/click/<아무토큰>?u=https://피싱} 로 우리 도메인 평판을 빌린 오픈
 * 리다이렉트를 만들 수 있다(AUDIT SEC-5). 발송 시점에 (토큰, URL)에 HMAC 서명을 붙이고
 * 리다이렉트 때 검증해 <b>우리가 실제로 발행한 링크만</b> 통과시킨다. 서명 키는 JWT 시크릿을
 * 재사용한다(운영에선 SecretsGuard 가 기본값 폴백을 막는다). 컨텍스트 프리픽스로 용도를 분리.
 */
@Component
public class TrackingLinkSigner {

    private static final String CONTEXT = "click-v1";
    private static final int SIG_BYTES = 16;   // 128비트로 절단 — URL 길이 절약, 위조엔 충분

    private final byte[] key;

    public TrackingLinkSigner(@Value("${app.jwt.secret:dev-only-not-a-real-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz}") String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** (token, url)에 대한 base64url 서명(패딩 없음). */
    public String sign(String token, String url) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(CONTEXT.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(token.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(url.getBytes(StandardCharsets.UTF_8));
            byte[] full = mac.doFinal();
            byte[] truncated = new byte[SIG_BYTES];
            System.arraycopy(full, 0, truncated, 0, SIG_BYTES);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign tracking link", e);
        }
    }

    /** 상수시간 비교로 서명 유효성 검사. */
    public boolean verify(String token, String url, String signature) {
        if (token == null || url == null || signature == null) {
            return false;
        }
        String expected = sign(token, url);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
