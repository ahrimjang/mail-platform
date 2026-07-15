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

    long countByCampaignIdAndStatus(Long campaignId, MessageStatus status);

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

    /** Delivered-mail count per contact (only list-campaign sends carry a contactId). */
    @Query("select m.contactId, count(m) from MailMessageEntity m "
            + "where m.contactId is not null and m.status = io.github.ahrimjang.mail.common.MessageStatus.SENT "
            + "group by m.contactId")
    java.util.List<Object[]> countSentByContact();

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
            where m.updated_at >= :since
              and m.status in ('SENT', 'FAILED', 'BOUNCED')
            group by d, m.status
            order by d
            """, nativeQuery = true)
    java.util.List<Object[]> aggregateDailyOutcomes(@Param("since") Instant since, @Param("zone") String zone);

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
}
