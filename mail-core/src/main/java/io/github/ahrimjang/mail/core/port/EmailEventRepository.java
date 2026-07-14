package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.EmailEvent;

/**
 * Persistence port for recorded recipient engagement events (opens/clicks).
 */
public interface EmailEventRepository {

    /** Persist a newly observed engagement event. */
    void save(EmailEvent event);

    /** Count distinct messages having at least one event of the given type in a campaign. */
    long countDistinctMessages(Long campaignId, EventType type);

    /** Distinct engaged messages of one variant (A/B) of a campaign. */
    long countDistinctMessagesByVariant(Long campaignId, EventType type, String variant);

    /**
     * Platform-wide daily engagement since {@code since}, bucketed by calendar day
     * in {@code zone}: distinct messages per (day, event type). Distinct counting
     * keeps the numbers duplicate-tolerant (the projection is at-least-once).
     */
    java.util.List<DailyEngagement> aggregateDailyEngagement(java.time.Instant since, java.time.ZoneId zone);

    /** One (day, type) distinct-message count of the daily engagement aggregation. */
    record DailyEngagement(java.time.LocalDate day, EventType type, long distinctMessages) {
    }
}
