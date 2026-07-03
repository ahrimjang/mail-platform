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

    Optional<MailMessageEntity> findByUnsubToken(String unsubToken);

    Optional<MailMessageEntity> findByTrackingToken(String trackingToken);

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
