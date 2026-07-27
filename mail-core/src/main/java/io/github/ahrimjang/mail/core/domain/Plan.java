package io.github.ahrimjang.mail.core.domain;

/**
 * 요금 플랜과 플랜별 한도 — 단일 출처. 정책 원문: docs/BILLING-policy.md.
 *
 * <p>청구 축은 월 발송량 하나(SENT 만 과금)이고, 연락처·멤버 수는 청구가 아니라
 * 플랜별 한도로만 쓴다. {@code null} 한도 = 무제한(엔터프라이즈는 협의).
 */
public enum Plan {

    STARTER(1_000L, 500L, 1, 5),
    STANDARD(10_000L, 5_000L, 3, 20),
    PRO(50_000L, 50_000L, 10, 50),
    ENTERPRISE(null, null, null, null);

    private final Long monthlySendLimit;
    private final Long contactLimit;
    private final Integer memberLimit;
    private final Integer sendRateCap;

    Plan(Long monthlySendLimit, Long contactLimit, Integer memberLimit, Integer sendRateCap) {
        this.monthlySendLimit = monthlySendLimit;
        this.contactLimit = contactLimit;
        this.memberLimit = memberLimit;
        this.sendRateCap = sendRateCap;
    }

    /** 월 발송(SENT) 한도 — 도달 시 신규 캠페인 등록이 차단된다. null = 무제한. */
    public Long monthlySendLimit() {
        return monthlySendLimit;
    }

    /** 보유 가능한 연락처 수 한도. null = 무제한. */
    public Long contactLimit() {
        return contactLimit;
    }

    /** 워크스페이스 멤버 수 한도. null = 무제한. */
    public Integer memberLimit() {
        return memberLimit;
    }

    /** 발송 속도 설정(토큰버킷 V19)의 플랜별 상한(건/초). null = 협의. */
    public Integer sendRateCap() {
        return sendRateCap;
    }
}
