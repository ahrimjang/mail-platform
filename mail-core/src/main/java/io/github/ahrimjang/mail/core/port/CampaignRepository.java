package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for campaigns. Implemented by an infra adapter.
 */
public interface CampaignRepository {

    Campaign save(Campaign campaign);

    Optional<Campaign> findById(Long id);

    /** Remove a campaign row entirely — only sensible for DRAFTs (no messages yet). */
    void deleteById(Long id);

    List<Campaign> findByWorkspace(Long workspaceId);

    void updateStatus(Long id, CampaignStatus status);

    /**
     * Scheduled campaigns whose send time has arrived but whose messages have
     * not been released to the queue yet ({@code enqueuedAt} is null and
     * {@code scheduledAt <= now}).
     */
    List<Campaign> findDueForEnqueue(Instant now);

    /**
     * Atomically claims a due campaign for release by stamping {@code enqueuedAt}
     * (single conditional update on {@code enqueuedAt IS NULL} — the database
     * serializes concurrent schedulers so only one wins). A canceled campaign
     * is never claimable: the update also requires {@code status = QUEUED}.
     *
     * @return true if this call won the claim; false means another scheduler
     *         already released it (or it was canceled) — the caller must skip,
     *         not error.
     */
    boolean claimForEnqueue(Long id, Instant now);

    /**
     * Atomically cancels a still-deferred scheduled campaign (single conditional
     * update on {@code enqueuedAt IS NULL AND status = QUEUED}). This races the
     * scheduler's {@link #claimForEnqueue} on the same row: whichever update
     * commits first wins, so a campaign is either released or canceled — never
     * both.
     *
     * @return true if the campaign flipped to CANCELED; false means it was
     *         already released (or never deferred) and cannot be canceled.
     */
    boolean claimForCancel(Long id);

    /**
     * QUEUED -> EXPANDING atomic claim for fan-out. True if this caller won
     * (redelivered jobs lose it). Single atomic conditional UPDATE.
     */
    boolean claimForFanout(Long id);

    /**
     * EXPANDING -> SENDING once fan-out finished creating+enqueuing all messages.
     * Single atomic conditional UPDATE.
     */
    void markExpanded(Long id);

    /**
     * QUEUED -> SENDING (dispatch marking first progress on an ad-hoc campaign).
     * True if it flipped. Single atomic conditional UPDATE.
     */
    boolean markSendingIfQueued(Long id);

    /**
     * SENDING -> COMPLETED once drained. No-op while EXPANDING, so fan-out in
     * progress is never completed early. Single atomic conditional UPDATE.
     */
    /** SENDING 에서만 COMPLETED 로 — @return 이 호출이 전이를 이겼는지 (알림 1회 발행의 근거). */
    boolean completeIfSending(Long id);

    /** Stamp when a winner-flow A/B campaign's test batch should be evaluated. */
    void scheduleAbEvaluation(Long id, Instant evaluateAt);

    /** Winner-flow campaigns due for evaluation (no winner yet, evaluate time passed). */
    List<Campaign> findDueForAbEvaluation(Instant now);

    /** Atomically claims the winner decision (single conditional UPDATE on ab_winner IS NULL). */
    boolean claimAbWinner(Long id, String winner);

    /**
     * 발송 중 중단(ARCH-8): QUEUED/EXPANDING/SENDING → CANCELED 조건부 UPDATE. 이미 끝났거나
     * 취소된 캠페인은 0행. 이긴 호출자가 PENDING 메시지를 일괄 취소하고, 팬아웃 루프와
     * 디스패치는 상태를 보고 스스로 멈춘다 — 이미 SMTP 로 넘어간 메시지는 회수할 수 없다.
     */
    boolean abort(Long id);

    // ── 복구 스위퍼(ARCH-1/5) — "claim 성공 → 후속 작업 중 실패"의 뒷정리 ──────────

    /** 팬아웃 도중 죽어 EXPANDING 에 {@code cutoff} 이전부터 머무는 캠페인. */
    List<Campaign> findStuckExpanding(Instant cutoff);

    /**
     * 고착 EXPANDING → QUEUED 조건부 되돌리기(같은 cutoff 조건 재확인). true 면 이 호출이
     * 되돌린 것 — 팬아웃을 재발행할 권리를 얻었다. 팬아웃은 이미 만든 메시지 뒤부터 재개한다.
     */
    boolean resetExpandingToQueued(Long id, Instant cutoff);

    /** 릴리스는 됐는데({@code enqueuedAt} 있음) {@code cutoff} 이전부터 QUEUED 인 캠페인. */
    List<Campaign> findOrphanQueued(Instant cutoff);

    // ── 플랫폼 운영자 화면 — 테넌트를 넘나드는 조회(의도적 격리 예외) ─────────────

    /**
     * 전 테넌트 캠페인 검색, 최신순 최대 {@code limit}. 조건은 전부 선택(null = 무시):
     * {@code q} 는 이름·제목·등록자 이메일 부분 일치. 어뷰즈 신고 대응·운영자 중단의 진입점.
     */
    List<Campaign> search(CampaignStatus status, Long workspaceId, String q, int limit);

    /** 지금 EXPANDING/SENDING 인 캠페인 전부 — 릴리스가 오래된 순(고착 의심이 위로). */
    List<Campaign> findInFlight();

    /** {@code since} 이후 발송 중 중단(abort)된 캠페인 수 — CANCELED 이면서 completedAt 이 찍힌 것. */
    long countAbortedSince(Instant since);
}
