package io.github.ahrimjang.mail.core.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Deterministic A/B variant assignment: a recipient always lands on the same
 * variant for a given split, so retries and re-expansion cannot flip anyone.
 *
 * <p>버킷은 이메일의 SHA-256 앞 8바이트를 10,000 으로 나눈 나머지다(ARCH-7). 예전의
 * {@code String.hashCode()} 는 비슷한 문자열(같은 도메인, 연번 아이디)이 뭉치는 성질이
 * 있어 소모수에서 테스트군이 한쪽 0명이 될 수 있었다 — 검증 안 된 안이 전체에 나간다.
 * 암호학적 해시는 그런 구조를 지워 작은 표본에서도 고르게 퍼진다.
 */
public final class AbVariantAssigner {

    private static final int BUCKETS = 10_000;

    private AbVariantAssigner() {
    }

    /** @return "B" for roughly splitPercent% of recipients, "A" otherwise. */
    public static String assign(String recipientEmail, int splitPercent) {
        return (bucket(recipientEmail) / 100) < splitPercent ? "B" : "A";
    }

    /**
     * Winner-flow assignment: only {@code testPercent}% of recipients enter the A/B
     * test (split between A and B by {@code splitPercent}); the rest return null and
     * are held back for the winning variant. Deterministic per email.
     */
    public static String assignWithHoldout(String recipientEmail, int testPercent, int splitPercent) {
        int bucket = bucket(recipientEmail);
        if (bucket >= testPercent * 100) {
            return null; // holdout — waits for the winner
        }
        return (bucket % 100) < splitPercent ? "B" : "A";
    }

    /** 0 ≤ bucket < 10,000 — 대소문자 무관, 같은 주소면 항상 같은 값. */
    static int bucket(String recipientEmail) {
        byte[] digest = sha256(recipientEmail.trim().toLowerCase(Locale.ROOT));
        long head = 0;
        for (int i = 0; i < 8; i++) {
            head = (head << 8) | (digest[i] & 0xff);
        }
        return (int) Math.floorMod(head, (long) BUCKETS);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // JDK 필수 알고리즘 — 일어나지 않는다
        }
    }
}
