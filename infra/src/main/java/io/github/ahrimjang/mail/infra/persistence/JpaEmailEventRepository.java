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
}
