package io.github.ahrimjang.mail.core.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 수신자 주소 품질 검증. 바운스는 사후 방어가 불가능한 피해다 — 한 번 나가면
 * SES 계정 전체(공유 IP)의 바운스율에 그대로 꽂히고, 5% 를 넘으면 경고, 10% 면
 * 계정 정지까지 간다. 그래서 명백히 배달 불가능한 주소는 <b>발송 전에</b> 걸러야 한다.
 *
 * <p>여기서 하는 것은 구문·명백한 쓰레기 판별까지다. 실제 사서함 존재 여부는
 * 보내봐야 알 수 있고(SMTP 검증은 차단당하기 쉬움), 그건 바운스 웹훅 → 억제 →
 * 워크스페이스 자동 정지의 3단 방어가 받는다.
 */
public final class EmailAddressValidator {

    private EmailAddressValidator() {
    }

    /** RFC 전문이 아니라 실무형: 공백·연속 점·꺾쇠 등 배달 불가 형태를 막는 수준. */
    private static final Pattern SHAPE = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    /**
     * 테스트·예시용으로 흔히 채워 넣는 도메인 — 전부 바운스로 돌아온다.
     *
     * <p>example.com/.org/.net 은 RFC 2606 예약 도메인이라 실사용자 명단에 들어올 일이
     * 없지만, 우리 테스트 코드가 표준 관례대로 쓰고 있어 목록에서 뺐다. 실제로 발송돼도
     * 바운스 웹훅 → 억제로 잡힌다.
     */
    private static final Set<String> INVALID_DOMAINS = Set.of(
            "test.com", "test.co.kr", "test.test", "sample.com", "domain.com",
            "asdf.com", "aaa.com", "none.com", "localhost", "test");

    /** 오타가 잦은 대형 도메인 — 사용자에게 교정 후보를 알려주기 위한 매핑. */
    private static final Map<String, String> TYPO_DOMAINS = Map.ofEntries(
            Map.entry("gmial.com", "gmail.com"), Map.entry("gmai.com", "gmail.com"),
            Map.entry("gmail.co", "gmail.com"), Map.entry("gmaill.com", "gmail.com"),
            Map.entry("gnail.com", "gmail.com"), Map.entry("gmail.con", "gmail.com"),
            Map.entry("naver.co", "naver.com"), Map.entry("navar.com", "naver.com"),
            Map.entry("nate.co", "nate.com"), Map.entry("hanmai.net", "hanmail.net"),
            Map.entry("hanmail.com", "hanmail.net"), Map.entry("daum.co", "daum.net"),
            Map.entry("hotmial.com", "hotmail.com"), Map.entry("outlok.com", "outlook.com"),
            Map.entry("yaho.com", "yahoo.com"), Map.entry("yahooo.com", "yahoo.com"));

    public enum Verdict {
        /** 배달 시도해도 되는 주소. */
        OK,
        /** 구문이 깨져 절대 배달 불가 — 조용히 건너뛴다. */
        MALFORMED,
        /** 테스트·예시 도메인 — 확실히 바운스한다. */
        INVALID_DOMAIN,
        /** 대형 도메인 오타로 보임 — 사용자에게 알리고 제외한다. */
        LIKELY_TYPO
    }

    public static Verdict check(String email) {
        if (email == null) {
            return Verdict.MALFORMED;
        }
        String trimmed = email.trim();
        if (trimmed.length() > 254 || !SHAPE.matcher(trimmed).matches()) {
            return Verdict.MALFORMED;
        }
        String local = trimmed.substring(0, trimmed.lastIndexOf('@'));
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..") || local.length() > 64) {
            return Verdict.MALFORMED;
        }
        String domain = trimmed.substring(trimmed.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (INVALID_DOMAINS.contains(domain)) {
            return Verdict.INVALID_DOMAIN;
        }
        if (TYPO_DOMAINS.containsKey(domain)) {
            return Verdict.LIKELY_TYPO;
        }
        return Verdict.OK;
    }

    public static boolean isSendable(String email) {
        return check(email) == Verdict.OK;
    }

    /** 오타 도메인의 교정 후보 — 없으면 null. */
    public static String suggestionFor(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        String domain = email.trim().substring(email.trim().lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        String fixed = TYPO_DOMAINS.get(domain);
        return fixed == null ? null : email.trim().substring(0, email.trim().lastIndexOf('@') + 1) + fixed;
    }
}
