package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.EmailEvent;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import org.springframework.stereotype.Repository;

/**
 * Adapter: implements the core {@link EmailEventRepository} port (open/click
 * engagement events) over Spring Data JPA.
 */
@Repository
public class JpaEmailEventRepository implements EmailEventRepository {

    private final EmailEventJpaRepository jpa;

    public JpaEmailEventRepository(EmailEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(EmailEvent e) {
        jpa.save(new EmailEventEntity(
                null, e.getMessageId(), e.getCampaignId(), e.getType(), e.getUrl(), e.getOccurredAt()));
    }

    @Override
    public long countDistinctMessages(Long campaignId, EventType type) {
        return jpa.countDistinctMessages(campaignId, type);
    }

    @Override
    public long countDistinctMessagesByVariant(Long campaignId, EventType type, String variant) {
        return jpa.countDistinctMessagesByVariant(campaignId, type, variant);
    }

    @Override
    public java.util.List<DailyEngagement> aggregateDailyEngagement(java.time.Instant since, java.time.ZoneId zone) {
        return jpa.aggregateDailyEngagement(since, zone.getId()).stream()
                .map(row -> new DailyEngagement(
                        ((java.sql.Date) row[0]).toLocalDate(),
                        EventType.valueOf((String) row[1]),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    public java.util.List<LinkClicks> topClickedLinks(java.time.Instant since, int limit) {
        return jpa.topClickedLinks(since, org.springframework.data.domain.PageRequest.of(0, limit)).stream()
                .map(row -> new LinkClicks(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    public java.util.List<ContactEvent> findRecentByContact(Long contactId, int limit) {
        return jpa.findRecentByContact(contactId, org.springframework.data.domain.PageRequest.of(0, limit)).stream()
                .map(row -> new ContactEvent(
                        (EventType) row[0],
                        (String) row[1],
                        (java.time.Instant) row[2],
                        (Long) row[3]))
                .toList();
    }

    @Override
    public java.util.List<HeatmapCell> aggregateOpenHeatmap(java.time.Instant since, java.time.ZoneId zone) {
        return jpa.aggregateOpenHeatmap(since, zone.getId()).stream()
                .map(row -> new HeatmapCell(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue(),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    public java.util.List<LinkClicks> linkClicksByCampaign(Long campaignId, int limit) {
        return jpa.linkClicksByCampaign(campaignId, org.springframework.data.domain.PageRequest.of(0, limit)).stream()
                .map(row -> new LinkClicks(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    public java.util.List<ContactEngagement> countEngagementByContact() {
        // Rows come as (contactId, type, count) — fold the two event types into
        // one record per contact.
        java.util.Map<Long, long[]> byContact = new java.util.LinkedHashMap<>();
        for (Object[] row : jpa.countEngagementByContact()) {
            long[] counts = byContact.computeIfAbsent((Long) row[0], k -> new long[2]);
            counts[row[1] == EventType.OPEN ? 0 : 1] = ((Number) row[2]).longValue();
        }
        return byContact.entrySet().stream()
                .map(e -> new ContactEngagement(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }
}
