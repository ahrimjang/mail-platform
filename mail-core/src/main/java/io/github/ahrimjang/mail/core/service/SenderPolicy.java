package io.github.ahrimjang.mail.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 발신 주소 정책. SES 는 검증된 신원에서만 발송을 허용하므로, 운영에서는 발신
 * 주소를 서비스 도메인(APP_SENDER_DOMAIN)으로 제한해야 발송 시점 거절(554)이
 * 안 난다. 도메인이 미설정(개발 기본)이면 제한하지 않아 로컬 동작은 그대로다.
 * 고객이 회신을 받고 싶은 주소는 Reply-To 로 — 제한 없음.
 */
@Service
public class SenderPolicy {

    private final String senderDomain;

    public SenderPolicy(@Value("${app.mail.sender-domain:}") String senderDomain) {
        this.senderDomain = senderDomain == null ? "" : senderDomain.trim().toLowerCase(Locale.ROOT);
    }

    /** 발신 주소 검사 — null/빈 값(기본 발신자 사용)은 항상 허용. */
    public void assertSenderAllowed(String senderEmail) {
        if (senderEmail == null || senderEmail.isBlank() || senderDomain.isEmpty()) {
            return;
        }
        if (!senderEmail.trim().toLowerCase(Locale.ROOT).endsWith("@" + senderDomain)) {
            throw new IllegalArgumentException(
                    "발신 주소는 @" + senderDomain + " 로 끝나야 해요. 회신을 받을 주소는 '회신 주소(Reply-To)'에 입력해주세요.");
        }
    }

    /** 회신 주소 형식 검사 — 형식만 보고 도메인은 제한하지 않는다. */
    public void assertReplyToValid(String replyTo) {
        if (replyTo != null && !replyTo.isBlank() && !replyTo.contains("@")) {
            throw new IllegalArgumentException("회신 주소 형식이 올바르지 않아요: " + replyTo);
        }
    }
}
