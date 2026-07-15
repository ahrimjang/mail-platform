package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.EventType;
import org.springframework.data.domain.Pageable;
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

    /** Ranked tracked URLs: raw clicks + distinct clicking messages, biggest first. */
    @Query("select e.url, count(e), count(distinct e.messageId) from EmailEventEntity e "
            + "where e.type = io.github.ahrimjang.mail.common.EventType.CLICK "
            + "and e.url is not null and e.occurredAt >= ?1 "
            + "group by e.url order by count(e) desc")
    java.util.List<Object[]> topClickedLinks(java.time.Instant since, Pageable pageable);

    /**
     * Open heatmap buckets: ISO weekday + local hour of day, distinct messages.
     * Columns: dw(int 1=Mon..7=Sun), hr(int 0..23), cnt(long).
     */
    @org.springframework.data.jpa.repository.Query(value = """
            select cast(extract(isodow from e.occurred_at at time zone :zone) as int) as dw,
                   cast(extract(hour from e.occurred_at at time zone :zone) as int) as hr,
                   count(distinct e.message_id) as cnt
            from email_events e
            where e.type = 'OPEN' and e.occurred_at >= :since
            group by dw, hr
            """, nativeQuery = true)
    java.util.List<Object[]> aggregateOpenHeatmap(
            @org.springframework.data.repository.query.Param("since") java.time.Instant since,
            @org.springframework.data.repository.query.Param("zone") String zone);

    /**
     * One contact's engagement events, newest first — joins events to their
     * message rows because events carry the message, not the contact.
     * Columns: type(EventType), url(text|null), occurredAt(Instant), campaignId(long).
     */
    @Query("select e.type, e.url, e.occurredAt, e.campaignId from EmailEventEntity e, MailMessageEntity m "
            + "where e.messageId = m.id and m.contactId = ?1 order by e.occurredAt desc")
    java.util.List<Object[]> findRecentByContact(Long contactId, Pageable pageable);

    /** One campaign's link ranking: raw clicks + distinct clicking messages. */
    @Query("select e.url, count(e), count(distinct e.messageId) from EmailEventEntity e "
            + "where e.campaignId = ?1 and e.type = io.github.ahrimjang.mail.common.EventType.CLICK "
            + "and e.url is not null group by e.url order by count(e) desc")
    java.util.List<Object[]> linkClicksByCampaign(Long campaignId, Pageable pageable);
}
