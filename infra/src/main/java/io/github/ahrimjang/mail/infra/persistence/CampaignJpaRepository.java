package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface CampaignJpaRepository extends JpaRepository<CampaignEntity, Long> {

    /** Scheduled campaigns that are due but not yet released to the queue (canceled ones excluded). */
    @Query("select c from CampaignEntity c where c.enqueuedAt is null and c.scheduledAt <= :now "
            + "and c.status = io.github.ahrimjang.mail.common.CampaignStatus.QUEUED")
    List<CampaignEntity> findDueForEnqueue(@Param("now") Instant now);

    /**
     * Single conditional UPDATE claiming a due campaign for release — same
     * pattern as {@link MailMessageJpaRepository#claimPending}: the database
     * serializes concurrent schedulers, so exactly one caller updates the row
     * and gets to publish the send jobs. The status guard makes this mutually
     * exclusive with {@link #claimForCancel} on the same row.
     */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.enqueuedAt = :now where c.id = :id and c.enqueuedAt is null "
            + "and c.status = io.github.ahrimjang.mail.common.CampaignStatus.QUEUED")
    int claimForEnqueue(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Single conditional UPDATE canceling a still-deferred campaign. Mirrors
     * {@link #claimForEnqueue}'s condition, so against a concurrent release the
     * database picks exactly one winner: released or canceled, never both.
     */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.status = io.github.ahrimjang.mail.common.CampaignStatus.CANCELED "
            + "where c.id = :id and c.enqueuedAt is null "
            + "and c.status = io.github.ahrimjang.mail.common.CampaignStatus.QUEUED")
    int claimForCancel(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.status = io.github.ahrimjang.mail.common.CampaignStatus.EXPANDING "
            + "where c.id = :id and c.status = io.github.ahrimjang.mail.common.CampaignStatus.QUEUED")
    int claimForFanout(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.status = io.github.ahrimjang.mail.common.CampaignStatus.SENDING "
            + "where c.id = :id and c.status = io.github.ahrimjang.mail.common.CampaignStatus.EXPANDING")
    int markExpanded(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.status = io.github.ahrimjang.mail.common.CampaignStatus.SENDING "
            + "where c.id = :id and c.status = io.github.ahrimjang.mail.common.CampaignStatus.QUEUED")
    int markSendingIfQueued(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.status = io.github.ahrimjang.mail.common.CampaignStatus.COMPLETED, "
            + "c.completedAt = :now "
            + "where c.id = :id and c.status = io.github.ahrimjang.mail.common.CampaignStatus.SENDING")
    int completeIfSending(@Param("id") Long id, @Param("now") Instant now);

    /** Winner-flow campaigns due for evaluation: no winner yet, evaluate time passed. */
    @Query("select c from CampaignEntity c where c.abWinner is null and c.abEvaluateAt is not null "
            + "and c.abEvaluateAt <= :now")
    List<CampaignEntity> findDueForAbEvaluation(@Param("now") Instant now);

    /** Stamps when the winner scheduler should evaluate the released test batch. */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.abEvaluateAt = :evaluateAt where c.id = :id")
    int scheduleAbEvaluation(@Param("id") Long id, @Param("evaluateAt") Instant evaluateAt);

    /**
     * Single conditional UPDATE claiming the winner decision — same pattern as
     * {@link #claimForEnqueue}: concurrent winner schedulers race on
     * {@code ab_winner IS NULL} and the database picks exactly one, so the held
     * remainder is only ever released once.
     */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.abWinner = :winner where c.id = :id and c.abWinner is null")
    int claimAbWinner(@Param("id") Long id, @Param("winner") String winner);
}
