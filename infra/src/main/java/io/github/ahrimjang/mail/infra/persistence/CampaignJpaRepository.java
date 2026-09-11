package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface CampaignJpaRepository extends JpaRepository<CampaignEntity, Long> {

    java.util.List<CampaignEntity> findByWorkspaceId(Long workspaceId);


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

    /** 발송 중 중단 — 아직 끝나지 않은 캠페인만 CANCELED 로(ARCH-8). */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.status = io.github.ahrimjang.mail.common.CampaignStatus.CANCELED, "
            + "c.completedAt = :now "
            + "where c.id = :id and c.status in (io.github.ahrimjang.mail.common.CampaignStatus.QUEUED, "
            + "                                  io.github.ahrimjang.mail.common.CampaignStatus.EXPANDING, "
            + "                                  io.github.ahrimjang.mail.common.CampaignStatus.SENDING)")
    int abort(@Param("id") Long id, @Param("now") Instant now);

    /** QUEUED→EXPANDING claim. 시작 시각도 찍는다 — 복구 스위퍼의 고착 판정 기준(V34). */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.status = io.github.ahrimjang.mail.common.CampaignStatus.EXPANDING, "
            + "c.expandingStartedAt = :now "
            + "where c.id = :id and c.status = io.github.ahrimjang.mail.common.CampaignStatus.QUEUED")
    int claimForFanout(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 팬아웃 도중 워커가 죽어 EXPANDING 에 남은 캠페인. 시작 시각이 없으면(도메인 save 가
     * 덮어쓴 경우) 생성 시각으로 대신 판정한다 — 아무 기준도 없는 것보다 낫다.
     */
    @Query("select c from CampaignEntity c where c.status = io.github.ahrimjang.mail.common.CampaignStatus.EXPANDING "
            + "and coalesce(c.expandingStartedAt, c.createdAt) < :cutoff")
    List<CampaignEntity> findStuckExpanding(@Param("cutoff") Instant cutoff);

    /**
     * 고착 EXPANDING → QUEUED 되돌리기. 같은 cutoff 조건을 다시 걸어, 그 사이 팬아웃이
     * 실제로 진행 중이었거나 다른 스위퍼가 먼저 되돌린 경우엔 0행 — 재발행하지 않는다.
     */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.status = io.github.ahrimjang.mail.common.CampaignStatus.QUEUED, "
            + "c.expandingStartedAt = null "
            + "where c.id = :id and c.status = io.github.ahrimjang.mail.common.CampaignStatus.EXPANDING "
            + "and coalesce(c.expandingStartedAt, c.createdAt) < :cutoff")
    int resetExpandingToQueued(@Param("id") Long id, @Param("cutoff") Instant cutoff);

    /**
     * 릴리스는 됐는데(enqueuedAt 있음) 오래도록 QUEUED 인 캠페인 — 발행 직후 크래시로
     * 잡이 사라졌거나, create() 가 저장 뒤 검증에서 실패해 남긴 잔존물.
     */
    @Query("select c from CampaignEntity c where c.status = io.github.ahrimjang.mail.common.CampaignStatus.QUEUED "
            + "and c.enqueuedAt is not null and c.enqueuedAt < :cutoff")
    List<CampaignEntity> findOrphanQueued(@Param("cutoff") Instant cutoff);

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
