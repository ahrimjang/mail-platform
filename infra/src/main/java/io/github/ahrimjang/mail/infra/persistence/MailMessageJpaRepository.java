package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface MailMessageJpaRepository extends JpaRepository<MailMessageEntity, Long> {

    long countByCampaignId(Long campaignId);

    /** 평판 방어용 — 워크스페이스의 최근 발송 시도(SENT+BOUNCED)와 바운스 수. */
    @org.springframework.data.jpa.repository.Query(
            "select count(m), coalesce(sum(case when m.status = io.github.ahrimjang.mail.common.MessageStatus.BOUNCED then 1 else 0 end), 0) "
            + "from MailMessageEntity m, CampaignEntity c "
            + "where m.campaignId = c.id and c.workspaceId = :workspaceId "
            + "and m.status in (io.github.ahrimjang.mail.common.MessageStatus.SENT, io.github.ahrimjang.mail.common.MessageStatus.BOUNCED) "
            + "and m.updatedAt >= :since")
    java.util.List<Object[]> workspaceBounceStats(
            @org.springframework.data.repository.query.Param("workspaceId") Long workspaceId,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    long countByCampaignIdAndStatus(Long campaignId, MessageStatus status);

    /**
     * 플랫폼 운영자 화면 — {@code since} 이후 (워크스페이스, 상태)별 건수와 최근 갱신 시각을
     * 그룹 쿼리 한 번으로. 캠페인을 경유해 테넌트를 해석한다(메시지 행엔 workspace_id 가 없다).
     */
    @org.springframework.data.jpa.repository.Query(
            "select c.workspaceId, m.status, count(m), max(m.updatedAt) "
            + "from MailMessageEntity m, CampaignEntity c "
            + "where c.id = m.campaignId and m.updatedAt >= :since "
            + "group by c.workspaceId, m.status")
    java.util.List<Object[]> aggregateByWorkspaceSince(@Param("since") Instant since);

    long countByStatusAndUpdatedAtGreaterThanEqual(MessageStatus status, Instant since);

    boolean existsByCampaignIdAndStatusIn(Long campaignId, java.util.Collection<MessageStatus> statuses);

    /** Per-variant delivery counts of an A/B campaign. Columns: variant(text), total(long), sent(long). */
    @Query("select m.variant as variant, count(m) as total, "
            + "sum(case when m.status = io.github.ahrimjang.mail.common.MessageStatus.SENT then 1 else 0 end) as sent "
            + "from MailMessageEntity m where m.campaignId = :campaignId and m.variant is not null "
            + "group by m.variant order by m.variant")
    java.util.List<Object[]> countByCampaignIdGroupByVariant(@Param("campaignId") Long campaignId);

    Optional<MailMessageEntity> findByUnsubToken(String unsubToken);

    Optional<MailMessageEntity> findByTrackingToken(String trackingToken);

    /** Ids only — a scheduled release just needs something to enqueue, not full rows. */
    @Query("select m.id from MailMessageEntity m where m.campaignId = :campaignId "
            + "and m.status = io.github.ahrimjang.mail.common.MessageStatus.PENDING")
    java.util.List<Long> findPendingIdsByCampaignId(@Param("campaignId") Long campaignId);

    /** PENDING test-batch rows of a winner-flow campaign (a variant was assigned). */
    @Query("select m.id from MailMessageEntity m where m.campaignId = :campaignId "
            + "and m.status = io.github.ahrimjang.mail.common.MessageStatus.PENDING "
            + "and m.variant is not null")
    java.util.List<Long> findPendingTestIdsByCampaignId(@Param("campaignId") Long campaignId);

    /** 테스트 배치(variant 있음)에 아직 안 끝난 행이 있는가 — 승자 판정을 미루는 근거. */
    @Query("select count(m) > 0 from MailMessageEntity m where m.campaignId = :campaignId "
            + "and m.variant is not null "
            + "and m.status in (io.github.ahrimjang.mail.common.MessageStatus.PENDING, "
            + "                 io.github.ahrimjang.mail.common.MessageStatus.SENDING)")
    boolean existsUnfinishedTestBatch(@Param("campaignId") Long campaignId);

    /** PENDING held rows of a winner-flow campaign (no variant — waiting for the winner). */
    @Query("select m.id from MailMessageEntity m where m.campaignId = :campaignId "
            + "and m.status = io.github.ahrimjang.mail.common.MessageStatus.PENDING "
            + "and m.variant is null")
    java.util.List<Long> findPendingHeldIdsByCampaignId(@Param("campaignId") Long campaignId);

    /**
     * Bulk-cancel a canceled campaign's PENDING rows. Safe as a plain bulk
     * update (no claim needed): the campaign lost its release race, so these
     * ids were never published and no consumer will ever process them.
     */
    @Modifying
    @Transactional
    @Query("update MailMessageEntity m set m.status = io.github.ahrimjang.mail.common.MessageStatus.CANCELED, "
            + "m.updatedAt = :now where m.campaignId = :campaignId "
            + "and m.status = io.github.ahrimjang.mail.common.MessageStatus.PENDING")
    int cancelPendingByCampaignId(@Param("campaignId") Long campaignId, @Param("now") Instant now);

    /** Send-log feed: latest state changes first (id breaks ties within the same instant). */
    java.util.List<MailMessageEntity> findByCampaignIdOrderByUpdatedAtDescIdDesc(
            Long campaignId, org.springframework.data.domain.Pageable pageable);

    /** One contact's deliveries, newest state change first (the recipient activity view). */
    @Query("select m from MailMessageEntity m where m.contactId = :contactId order by m.updatedAt desc")
    java.util.List<MailMessageEntity> findRecentByContact(@Param("contactId") Long contactId,
                                                          org.springframework.data.domain.Pageable pageable);

    /** Usage meter: SENT mail of one workspace since an instant (joined through campaigns). */
    @Query("select count(m) from MailMessageEntity m, CampaignEntity c "
            + "where c.id = m.campaignId and c.workspaceId = :ws "
            + "and m.status = io.github.ahrimjang.mail.common.MessageStatus.SENT "
            + "and m.updatedAt >= :since")
    long countSentByWorkspaceSince(@Param("ws") Long workspaceId, @Param("since") Instant since);

    /**
     * Delivered-mail count per contact (only list-campaign sends carry a contactId),
     * 한 워크스페이스·최근 기간으로 한정. 캠페인을 경유해 테넌트를 거른다.
     */
    @Query("select m.contactId, count(m) from MailMessageEntity m, CampaignEntity c "
            + "where c.id = m.campaignId and c.workspaceId = :ws "
            + "and m.contactId is not null and m.status = io.github.ahrimjang.mail.common.MessageStatus.SENT "
            + "and m.updatedAt >= :since "
            + "group by m.contactId")
    java.util.List<Object[]> countSentByContact(@Param("ws") Long workspaceId, @Param("since") Instant since);

    /**
     * Grouped send log: collapse state changes into fixed time buckets per status,
     * newest bucket first. Aggregation happens in the database so the result stays
     * small for arbitrarily large campaigns. Columns: bucket(long), status(text),
     * cnt(long), sample_error(text|null).
     */
    @Query(value = """
            select floor(extract(epoch from m.updated_at) / :bucketSeconds) as bucket,
                   m.status as status,
                   count(*) as cnt,
                   min(m.error_message) as sample_error
            from mail_messages m
            where m.campaign_id = :campaignId
            group by bucket, m.status
            order by bucket desc, m.status
            limit :limit
            """, nativeQuery = true)
    java.util.List<Object[]> aggregateLogByCampaign(@Param("campaignId") Long campaignId,
                                                    @Param("bucketSeconds") int bucketSeconds,
                                                    @Param("limit") int limit);

    /**
     * Dashboard series: platform-wide terminal outcomes per calendar day.
     * `at time zone :zone` converts the timestamptz into the console's local
     * calendar before the date cast, so a send at 01:00 KST doesn't land on
     * the previous (UTC) day. Columns: d(date), status(text), cnt(long).
     */
    @Query(value = """
            select cast(m.updated_at at time zone :zone as date) as d,
                   m.status as status,
                   count(*) as cnt
            from mail_messages m
            join campaigns c on c.id = m.campaign_id
            where c.workspace_id = :workspaceId
              and m.updated_at >= :since
              and m.status in ('SENT', 'FAILED', 'BOUNCED')
            group by d, m.status
            order by d
            """, nativeQuery = true)
    java.util.List<Object[]> aggregateDailyOutcomes(@Param("workspaceId") Long workspaceId,
                                                    @Param("since") Instant since, @Param("zone") String zone);

    /**
     * Single conditional UPDATE — the row-level lock Postgres takes while evaluating
     * this statement is what makes the claim atomic; a concurrent caller targeting the
     * same id either updates 0 rows (lost the race) or blocks until this transaction
     * commits and then sees the row is no longer eligible. Also reclaims a message
     * stuck in SENDING past {@code staleBefore} (a previous claimant crashed mid-send).
     */
    @Modifying
    @Transactional
    @Query("update MailMessageEntity m set m.status = io.github.ahrimjang.mail.common.MessageStatus.SENDING, "
            + "m.updatedAt = :now "
            + "where m.id = :id "
            + "and (m.status = io.github.ahrimjang.mail.common.MessageStatus.PENDING "
            + "or (m.status = io.github.ahrimjang.mail.common.MessageStatus.SENDING and m.updatedAt < :staleBefore))")
    int claimPending(@Param("id") Long id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    /**
     * 종료 상태 기록 — claim 토큰이 아직 유효할 때만. {@code updatedAt = :claimedAt} 이
     * 곧 "내가 claim 한 그 행 그대로"라는 증거다: stale 재클레임이나 바운스 웹훅이 먼저
     * 썼으면 updatedAt 이 바뀌어 0행이 되고, 남의 결과를 덮어쓰지 않는다.
     */
    @Modifying
    @Transactional
    @Query("update MailMessageEntity m set m.status = :status, m.errorMessage = :error, "
            + "m.updatedAt = :now, m.attempts = m.attempts + 1 "
            + "where m.id = :id and m.status = io.github.ahrimjang.mail.common.MessageStatus.SENDING "
            + "and m.updatedAt = :claimedAt")
    int finish(@Param("id") Long id, @Param("claimedAt") Instant claimedAt, @Param("status") MessageStatus status,
               @Param("error") String error, @Param("now") Instant now);

    /** 토큰 없는 종료 기록 — 아직 살아 있는 행에만(DLQ 뒷정리). */
    @Modifying
    @Transactional
    @Query("update MailMessageEntity m set m.status = :status, m.errorMessage = :error, "
            + "m.updatedAt = :now, m.attempts = m.attempts + 1 "
            + "where m.id = :id and m.status in (io.github.ahrimjang.mail.common.MessageStatus.PENDING, "
            + "                                  io.github.ahrimjang.mail.common.MessageStatus.SENDING)")
    int finishIfActive(@Param("id") Long id, @Param("status") MessageStatus status,
                       @Param("error") String error, @Param("now") Instant now);

    /** 비동기 바운스 — SENT/SENDING 에서만 BOUNCED 로(이미 종료된 다른 상태는 보존, 멱등). */
    @Modifying
    @Transactional
    @Query("update MailMessageEntity m set m.status = io.github.ahrimjang.mail.common.MessageStatus.BOUNCED, "
            + "m.errorMessage = :reason, m.updatedAt = :now "
            + "where m.id = :id and m.status in (io.github.ahrimjang.mail.common.MessageStatus.SENT, "
            + "                                  io.github.ahrimjang.mail.common.MessageStatus.SENDING)")
    int markBouncedIfDelivered(@Param("id") Long id, @Param("reason") String reason, @Param("now") Instant now);

    /**
     * 복구 스위퍼용 — 릴리스된 캠페인에서 오래도록 PENDING/SENDING 인 메시지. 잡이 사라진
     * 고아(발행 직후 크래시, 팬아웃 중 사망), stale SENDING(발송 중 사망), 승자 확정 뒤
     * 릴리스에 실패한 홀드아웃이 전부 여기 걸린다. 승자가 아직 없는 홀드아웃(variant null)
     * 만은 정상 대기이므로 제외한다. 재발행은 dispatch 의 claim 이 멱등하게 받는다.
     */
    @Query("select m.id from MailMessageEntity m, CampaignEntity c "
            + "where c.id = m.campaignId "
            + "and c.enqueuedAt is not null and c.enqueuedAt < :cutoff "
            + "and c.status in (io.github.ahrimjang.mail.common.CampaignStatus.QUEUED, "
            + "                 io.github.ahrimjang.mail.common.CampaignStatus.EXPANDING, "
            + "                 io.github.ahrimjang.mail.common.CampaignStatus.SENDING) "
            + "and m.updatedAt < :cutoff "
            + "and m.status in (io.github.ahrimjang.mail.common.MessageStatus.PENDING, "
            + "                 io.github.ahrimjang.mail.common.MessageStatus.SENDING) "
            + "and not (m.variant is null and c.abTestPercent is not null and c.abWinner is null) "
            + "order by m.id")
    java.util.List<Long> findStaleIds(@Param("cutoff") Instant cutoff, org.springframework.data.domain.Pageable pageable);

    /**
     * 재발행한 PENDING 행의 updatedAt 을 지금으로 — 다음 스위프에서 곧바로 또 잡히지 않게.
     * (SENDING 행은 dispatch 의 claim 이 갱신하므로 건드리지 않는다.)
     */
    @Modifying
    @Transactional
    @Query("update MailMessageEntity m set m.updatedAt = :now where m.id in :ids "
            + "and m.status = io.github.ahrimjang.mail.common.MessageStatus.PENDING")
    int touchPending(@Param("ids") java.util.Collection<Long> ids, @Param("now") Instant now);

    /** 팬아웃 재개 커서 — 이 캠페인에 이미 만들어진 메시지 중 가장 큰 contactId (없으면 null). */
    @Query("select max(m.contactId) from MailMessageEntity m where m.campaignId = :campaignId")
    Long maxContactIdByCampaignId(@Param("campaignId") Long campaignId);
}
