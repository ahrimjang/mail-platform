package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.MailMessage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the per-recipient send queue.
 */
public interface MailMessageRepository {

    /** Bulk-insert freshly enqueued messages, returning the saved rows. */
    List<MailMessage> saveAll(List<MailMessage> messages);

    /** Persist a single message after a state change (sent/failed). */
    MailMessage save(MailMessage message);

    /** Look up a message by its id. */
    Optional<MailMessage> findById(Long id);

    /**
     * Atomically claims a message for processing by flipping PENDING -&gt; SENDING
     * (single conditional update; the database serializes concurrent callers so
     * only one wins). A message already SENDING for longer than {@code staleAfter}
     * is also reclaimable — that implies whoever claimed it crashed before
     * finishing, so a redelivered queue job must still be able to pick it back up.
     *
     * @return true if this call won the claim; false if the message is missing or
     *         is being actively processed by another consumer right now — the
     *         caller must treat false as a safe no-op, not an error.
     */
    Optional<java.time.Instant> claim(Long messageId, Duration staleAfter);

    /**
     * 종료 상태 기록 — claim 토큰({@code claimedAt}, claim 이 찍은 updatedAt)이 아직 유효할 때만
     * 쓴다: {@code status = SENDING and updatedAt = claimedAt} 조건부 UPDATE. 그 사이 다른
     * 워커가 stale 재클레임했거나 바운스 웹훅이 먼저 썼으면 0행 — 남의 결과를 덮어쓰지 않는다
     * (ARCH-4 lost update). 이전엔 blind save 라 늦게 끝난 쪽이 무조건 이겼다.
     *
     * @return true 면 이 호출이 종료 상태를 확정했다
     */
    boolean finish(Long messageId, java.time.Instant claimedAt, io.github.ahrimjang.mail.common.MessageStatus status,
                   String errorMessage, java.time.Instant now);

    /**
     * 토큰 없는 종료 기록 — 아직 살아 있는(PENDING/SENDING) 행에만. DLQ 뒷정리처럼 claim 을
     * 거치지 않는 경로용. 이미 종료된 행은 0행.
     */
    boolean finishIfActive(Long messageId, io.github.ahrimjang.mail.common.MessageStatus status,
                           String errorMessage, java.time.Instant now);

    /**
     * 비동기 바운스 반영 — SENT(정상 배달 후 반송) 또는 SENDING(발송 중 반송 통보) 에서만
     * BOUNCED 로. 이미 BOUNCED/FAILED 면 0행이라 이벤트가 두 번 나가지 않는다(멱등).
     */
    boolean markBounced(Long messageId, String reason, java.time.Instant now);

    /** Aggregate per-status counts for one campaign. */
    MessageCounts countByCampaign(Long campaignId);

    /** Per-variant (A/B) delivery counts of a campaign: one row per variant. */
    List<VariantDelivery> countByCampaignAndVariant(Long campaignId);

    /** One variant's delivery counts. */
    record VariantDelivery(String variant, long total, long sent) {}

    /**
     * Cheap drain check: true if the campaign still has any PENDING or SENDING
     * message. Short-circuits on the first hit — used on the hot dispatch path
     * instead of a full per-status count.
     */
    boolean hasPendingOrSending(Long campaignId);

    /** Ids of a campaign's PENDING messages — what a scheduled release enqueues. */
    List<Long> findPendingIdsByCampaign(Long campaignId);

    /** PENDING test-batch ids (variant assigned) — what a scheduled winner-flow release enqueues. */
    List<Long> findPendingTestIdsByCampaign(Long campaignId);

    /** PENDING held ids (no variant) — released with the winner's content once decided. */
    List<Long> findPendingHeldIdsByCampaign(Long campaignId);

    // ── 복구 스위퍼(ARCH-1/2/5) ────────────────────────────────────────────

    /**
     * 릴리스된 캠페인에서 {@code cutoff} 이전부터 PENDING/SENDING 인 메시지 id(최대 limit).
     * 잡이 사라진 고아·stale SENDING·릴리스 실패한 홀드아웃이 걸리고, 승자 미정의 홀드아웃은
     * 정상 대기라 제외된다. 재발행은 dispatch 의 claim 이 멱등하게 받는다.
     */
    List<Long> findStaleIds(java.time.Instant cutoff, int limit);

    /** 재발행한 PENDING 행의 updatedAt 갱신 — 다음 스위프에서 또 잡히지 않게. @return 갱신 행 수 */
    int touchPending(List<Long> ids, java.time.Instant now);

    /** 팬아웃 재개 커서: 이미 만들어진 메시지의 최대 contactId — 없으면 null. */
    Long maxContactIdByCampaign(Long campaignId);

    /**
     * Flips every PENDING message of the campaign to CANCELED (bulk update).
     * Only meaningful after {@link CampaignRepository#claimForCancel} won —
     * those rows were never published to the queue, so no consumer can race this.
     *
     * @return number of messages canceled
     */
    int cancelPendingByCampaign(Long campaignId);

    /** Most recently updated messages of a campaign (per-recipient drill-down), newest first. */
    List<MailMessage> findRecentByCampaign(Long campaignId, int limit);

    /** This contact's deliveries, newest first. */
    List<MailMessage> findRecentByContact(Long contactId, int limit);

    /** Delivered (SENT) mail this workspace produced since {@code since} — the usage meter. */
    long countSentByWorkspaceSince(Long workspaceId, java.time.Instant since);

    /** Delivered (SENT) mail count per contact; contacts with none are absent. */
    List<ContactSentCount> countSentByContact();

    /** One contact's delivered-mail count. */
    record ContactSentCount(Long contactId, long sent) {
    }

    /**
     * Send log aggregated into fixed time buckets: one row per (bucket, status)
     * with a count, newest bucket first. The database does the grouping so the
     * feed stays bounded no matter how many recipients a campaign has.
     */
    List<SendLogBucket> aggregateLogByCampaign(Long campaignId, int bucketSeconds, int limit);

    /** One aggregated bucket row; {@code sampleError} carries a representative failure reason. */
    record SendLogBucket(java.time.Instant bucketStart, io.github.ahrimjang.mail.common.MessageStatus status,
                         long count, String sampleError) {
    }

    /**
     * Platform-wide daily outcome counts since {@code since} (terminal statuses
     * only), bucketed by calendar day in {@code zone} — feeds the dashboard chart.
     * Days with no activity are simply absent; the caller fills gaps.
     */
    List<DailyOutcome> aggregateDailyOutcomes(Long workspaceId, java.time.Instant since, java.time.ZoneId zone);

    /** One (day, status) count of the daily outcome aggregation. */
    record DailyOutcome(java.time.LocalDate day, io.github.ahrimjang.mail.common.MessageStatus status, long count) {
    }

    /** Look up a message by its per-message unsubscribe token. */
    Optional<MailMessage> findByUnsubToken(String token);

    /** Look up a message by its per-message tracking token. */
    Optional<MailMessage> findByTrackingToken(String trackingToken);

    /** Snapshot of delivery progress for a campaign. SENDING messages are in-flight, not yet terminal. */
    record MessageCounts(long total, long pending, long sending, long sent, long failed, long bounced, long suppressed) {
    }

    /** 평판 방어용 — 워크스페이스의 최근 발송 시도(SENT+BOUNCED) 대비 바운스 집계. */
    WorkspaceBounceStats workspaceBounceStats(Long workspaceId, java.time.Instant since);

    record WorkspaceBounceStats(long attempted, long bounced) {
        public double bounceRate() {
            return attempted == 0 ? 0 : (double) bounced / attempted;
        }
    }
}
