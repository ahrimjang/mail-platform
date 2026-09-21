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
 * 리다이렉트 때 검증해 <b>우리가 실제로 발행한 링크만</b> 통과시킨다.
 *
 * <p><b>서명은 워커(발송)가, 검증은 api(리다이렉트)가 한다 — 두 프로세스가 반드시 같은
 * 키를 봐야 한다.</b> 한쪽만 주입되면 모든 클릭 링크가 조용히 400 으로 죽는다(실제로 겪음).
 * 그래서 JWT 시크릿을 빌려 쓰지 않고 전용 키 {@code APP_TRACKING_SIGNING_KEY} 를 두고,
 * 운영 compose 가 api·worker 양쪽 env 에 같은 값을 넣는다(미설정 시 JWT 시크릿으로 폴백).
 *
 * <p><b>키 교체는 이전 키를 함께 들고 있어야 한다.</b> 서명에 만료가 없는 대신, 이미 받은편지함에
 * 가 있는 메일의 링크는 발송 당시 키로 서명돼 있다 — 키를 한 번에 갈아치우면 과거 발송분의 클릭이
 * 전부 400 으로 죽는다. 그래서 {@code APP_TRACKING_PREVIOUS_SIGNING_KEYS}(쉼표 구분)에 물러난
 * 키를 남겨 두고, <b>서명은 언제나 현재 키로 · 검증은 현재+이전 키 전부</b>로 한다. 옛 캠페인의
 * 클릭이 실질적으로 끊길 때까지 두었다가 목록에서 빼면 교체가 끝난다. 교체 절차는
 * docs/OPS-MANUAL.md 참고.
 */
@Component
public class TrackingLinkSigner {

    private static final String CONTEXT = "click-v1";
    private static final int SIG_BYTES = 16;   // 128비트로 절단 — URL 길이 절약, 위조엔 충분

    /** 검증에 쓰는 키 전부. 0번이 현재 키이고, 서명에는 그것만 쓴다. */
    private final java.util.List<byte[]> keys;

    /** 이전 키 없이 현재 키 하나만 — 로컬·테스트용. */
    public TrackingLinkSigner(String secret) {
        this(secret, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TrackingLinkSigner(
            @Value("${app.tracking.signing-key:dev-only-not-a-real-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz}") String secret,
            @Value("${app.tracking.previous-signing-keys:}") String previousSecrets) {
        java.util.List<byte[]> all = new java.util.ArrayList<>();
        all.add(secret.getBytes(StandardCharsets.UTF_8));
        for (String previous : (previousSecrets == null ? "" : previousSecrets).split(",")) {
            String trimmed = previous.trim();
            // 빈 조각은 건너뛴다 — 미설정("")·후행 쉼표·오타로 빈 키가 검증 목록에 끼면
            // 같은 값으로 서명한 위조 링크가 통과한다.
            if (!trimmed.isEmpty()) {
                all.add(trimmed.getBytes(StandardCharsets.UTF_8));
            }
        }
        this.keys = java.util.List.copyOf(all);
    }

    /** (token, url)에 대한 base64url 서명(패딩 없음). 언제나 현재 키로 서명한다. */
    public String sign(String token, String url) {
        return sign(keys.get(0), token, url);
    }

    private static String sign(byte[] key, String token, String url) {
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

    /** 현재 키부터 이전 키까지 차례로, 상수시간 비교로 서명 유효성 검사. */
    public boolean verify(String token, String url, String signature) {
        if (token == null || url == null || signature == null) {
            return false;
        }
        byte[] given = signature.getBytes(StandardCharsets.UTF_8);
        for (byte[] key : keys) {
            if (MessageDigest.isEqual(sign(key, token, url).getBytes(StandardCharsets.UTF_8), given)) {
                return true;
            }
        }
        return false;
    }
}
