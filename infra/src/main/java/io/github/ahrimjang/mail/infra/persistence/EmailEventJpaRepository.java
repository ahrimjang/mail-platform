package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailEventJpaRepository extends JpaRepository<EmailEventEntity, Long> {

    @Query("select count(distinct e.messageId) from EmailEventEntity e where e.campaignId = ?1 and e.type = ?2")
    long countDistinctMessages(Long campaignId, EventType type);

    /** Distinct engaged messages of one A/B variant — joins events to their message rows. */
    @Query("select count(distinct e.messageId) from EmailEventEntity e, MailMessageEntity m "
            + "where e.messageId = m.id and e.campaignId = ?1 and e.type = ?2 and m.variant = ?3")
    long countDistinctMessagesByVariant(Long campaignId, EventType type, String variant);

    /**
     * Dashboard series: distinct engaged messages per (calendar day, event type).
     * Same local-calendar bucketing as the outcome aggregation (see
     * {@link MailMessageJpaRepository#aggregateDailyOutcomes}).
     * Columns: d(date), type(text), cnt(long).
     */
    @org.springframework.data.jpa.repository.Query(value = """
            select cast(e.occurred_at at time zone :zone as date) as d,
                   e.type as type,
                   count(distinct e.message_id) as cnt
            from email_events e
            where e.occurred_at >= :since
            group by d, e.type
            order by d
            """, nativeQuery = true)
    java.util.List<Object[]> aggregateDailyEngagement(
            @org.springframework.data.repository.query.Param("since") java.time.Instant since,
            @org.springframework.data.repository.query.Param("zone") String zone);
}
