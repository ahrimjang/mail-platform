package io.github.ahrimjang.mail.common;

/**
 * 요금제 페이지가 그리는 플랜 한 장 — 출처는 코어의 Plan enum 하나뿐이라
 * 가격/한도를 바꾸면 페이지가 따라온다. null 한도 = 무제한/협의.
 */
public record PlanView(
        String name,
        Integer monthlyPriceKrw,
        Long monthlySendLimit,
        Long contactLimit,
        Integer memberLimit,
        Integer sendRateCap
) {
}
