package io.github.ahrimjang.mail.common;

import java.util.List;

/**
 * Outcome of a CSV contact import.
 *
 * @param imported number of new contacts created
 * @param skipped  number of lines skipped (already existing)
 * @param rejected 배달 불가로 판정해 제외한 줄 수 (구문 오류·테스트 도메인·오타 의심)
 * @param samples  거부 사례 최대 10건 — 사용자가 명단을 고칠 수 있게 이유와 함께 돌려준다
 */
public record ImportResult(
        int imported,
        int skipped,
        int rejected,
        List<RejectedRow> samples
) {
    /** 거부된 한 줄. {@code suggestion} 은 오타 교정 후보(없으면 null). */
    public record RejectedRow(String email, String reason, String suggestion) {
    }

    /** rejected 도입 이전 시그니처 호환. */
    public ImportResult(int imported, int skipped) {
        this(imported, skipped, 0, List.of());
    }
}
