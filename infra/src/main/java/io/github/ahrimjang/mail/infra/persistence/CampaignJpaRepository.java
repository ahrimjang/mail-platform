package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface CampaignJpaRepository extends JpaRepository<CampaignEntity, Long> {

    /** Scheduled campaigns that are due but not yet released to the queue. */
    @Query("select c from CampaignEntity c where c.enqueuedAt is null and c.scheduledAt <= :now")
    List<CampaignEntity> findDueForEnqueue(@Param("now") Instant now);

    /**
     * Single conditional UPDATE claiming a due campaign for release — same
     * pattern as {@link MailMessageJpaRepository#claimPending}: the database
     * serializes concurrent schedulers, so exactly one caller updates the row
     * and gets to publish the send jobs.
     */
    @Modifying
    @Transactional
    @Query("update CampaignEntity c set c.enqueuedAt = :now where c.id = :id and c.enqueuedAt is null")
    int claimForEnqueue(@Param("id") Long id, @Param("now") Instant now);
}
