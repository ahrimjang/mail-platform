package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailEventJpaRepository extends JpaRepository<EmailEventEntity, Long> {

    @Query("select count(distinct e.messageId) from EmailEventEntity e where e.campaignId = ?1 and e.type = ?2")
    long countDistinctMessages(Long campaignId, EventType type);
}
