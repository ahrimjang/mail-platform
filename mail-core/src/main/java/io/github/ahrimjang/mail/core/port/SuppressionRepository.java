package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Suppression;

import java.util.Optional;

/**
 * Persistence port for the global suppression list. Implemented by an infra adapter.
 */
public interface SuppressionRepository {

    void save(Suppression s);

    boolean existsByWorkspaceAndEmail(Long workspaceId, String email);

    /** Which of the given addresses are suppressed in this workspace (batch check). */
    java.util.List<String> findSuppressedEmails(Long workspaceId, java.util.List<String> emails);

    Optional<Suppression> findByWorkspaceAndEmail(Long workspaceId, String email);

    /** Remove the address from the suppression list; a no-op if not present. */
    void deleteByWorkspaceAndEmail(Long workspaceId, String email);

    /** Total number of suppressed addresses (dashboard audience health). */
    long countByWorkspace(Long workspaceId);

    /** Suppressions grouped by reason ("bounce"/"unsubscribe"/"manual"), largest first. */
    java.util.List<ReasonCount> countByReason(Long workspaceId);

    /** Same breakdown, restricted to entries created since {@code since}. */
    java.util.List<ReasonCount> countByReasonSince(Long workspaceId, java.time.Instant since);

    /** One reason's suppression count. */
    record ReasonCount(String reason, long count) {
    }

    /**
     * 억제 목록 한 페이지 — 최근 등록순. {@code q} 는 이메일 부분 일치(빈 문자열 = 전체),
     * {@code reason} 은 정확 일치(null = 전체). 콘솔이 "무엇이, 왜, 언제" 억제됐는지
     * 보는 유일한 창구다.
     */
    java.util.List<Suppression> page(Long workspaceId, String q, String reason, int offset, int limit);

    /** {@link #page} 와 같은 조건의 전체 건수. */
    long countSearch(Long workspaceId, String q, String reason);
}
