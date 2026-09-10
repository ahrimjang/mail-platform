package io.github.ahrimjang.mail.common;

import java.time.Instant;
import java.util.List;

/**
 * 억제 목록 한 페이지 — 워크스페이스가 더 이상 보내면 안 되는 주소 전체.
 *
 * <p>연락처 화면의 "수신거부" 필터는 연락처와 조인해 보여주므로, 직접 입력으로 보낸
 * 주소(연락처가 아닌)가 바운스되면 어디에서도 보이지 않았다. 이 뷰는 억제 테이블을
 * 그대로 내보내 사유·시각·해제까지 한 화면에서 다루게 한다.
 *
 * @param items    이 페이지의 억제 항목 (최근 등록순)
 * @param total    필터 조건에 맞는 전체 건수
 * @param byReason 사유별 건수 (필터 무관 — 명단 건강도 요약용)
 */
public record SuppressionPageView(
        List<Item> items,
        long total,
        List<ReasonCount> byReason
) {

    /**
     * @param reason hard_bounce · complaint · bounce · unsubscribe · manual
     */
    public record Item(String email, String reason, Instant createdAt) {
    }

    public record ReasonCount(String reason, long count) {
    }
}
