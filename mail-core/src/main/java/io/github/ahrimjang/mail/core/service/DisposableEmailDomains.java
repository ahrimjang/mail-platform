package io.github.ahrimjang.mail.core.service;

import java.util.Locale;
import java.util.Set;

/**
 * 일회용(disposable) 이메일 도메인 차단 목록. 무료 플랜(월 1,000통)을 노린 계정
 * 양산 — 일회용 주소로 무한 가입해 발송량을 공짜로 쌓는 패턴 — 의 1차 방어선이다.
 * 전수 목록이 아니라 널리 쓰이는 서비스 위주의 정적 목록: 완벽 차단이 목적이 아니라
 * 자동화된 대량 가입의 비용을 올리는 게 목적이다.
 */
public final class DisposableEmailDomains {

    private DisposableEmailDomains() {
    }

    private static final Set<String> DOMAINS = Set.of(
            "mailinator.com", "guerrillamail.com", "guerrillamail.net", "guerrillamail.org",
            "sharklasers.com", "10minutemail.com", "10minutemail.net", "temp-mail.org",
            "tempmail.com", "tempmail.dev", "tempmailo.com", "throwawaymail.com",
            "yopmail.com", "yopmail.fr", "yopmail.net", "trashmail.com", "trashmail.de",
            "getnada.com", "nada.email", "maildrop.cc", "dispostable.com", "mintemail.com",
            "mytemp.email", "mohmal.com", "fakeinbox.com", "spamgourmet.com", "mailnesia.com",
            "tempr.email", "discard.email", "burnermail.io", "emailondeck.com", "moakt.com",
            "tmpmail.org", "tmpmail.net", "inboxkitten.com", "33mail.com", "spambog.com",
            "mail-temp.com", "disposablemail.com", "tempinbox.com"
    );

    /** 이메일의 도메인이 알려진 일회용 서비스인지. */
    public static boolean isDisposable(String email) {
        if (email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        return DOMAINS.contains(email.substring(at + 1).trim().toLowerCase(Locale.ROOT));
    }
}
