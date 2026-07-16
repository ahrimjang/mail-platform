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
    java.util.List<DailyEngagement> aggregateDailyEngagement(Long workspaceId, java.time.Instant since, java.time.ZoneId zone);

    /** One (day, type) distinct-message count of the daily engagement aggregation. */
    record DailyEngagement(java.time.LocalDate day, EventType type, long distinctMessages) {
    }

    /**
     * Most-clicked tracked URLs since {@code since}, best first — the analytics
     * link ranking. Untracked events (null url) are excluded.
     */
    java.util.List<LinkClicks> topClickedLinks(Long workspaceId, java.time.Instant since, int limit);

    /** One ranked link: raw click count plus distinct clicking messages. */
    record LinkClicks(String url, long clicks, long uniqueMessages) {
    }

    /** One campaign's clicked links, best first — the detail page's link table. */
    java.util.List<LinkClicks> linkClicksByCampaign(Long campaignId, int limit);

    /**
     * Opens bucketed by (ISO weekday, local hour) since {@code since} — the
     * "when do people read" heatmap. Distinct messages per bucket.
     */
    java.util.List<HeatmapCell> aggregateOpenHeatmap(Long workspaceId, java.time.Instant since, java.time.ZoneId zone);

    /** One heatmap bucket: ISO weekday (1=Mon..7=Sun), hour 0..23, distinct opens. */
    record HeatmapCell(int dayOfWeek, int hour, long opens) {
    }

    /** This contact's engagement events (via their messages), newest first. */
    java.util.List<ContactEvent> findRecentByContact(Long contactId, int limit);

    /** One engagement event of a contact: what, on which campaign, when. */
    record ContactEvent(EventType type, String url, java.time.Instant occurredAt, Long campaignId) {
    }

    /**
     * Distinct opened/clicked message counts per contact (via the messages the
     * events point at); contacts without any engagement are absent.
     */
    java.util.List<ContactEngagement> countEngagementByContact();

    /** One contact's engagement counters (distinct messages per type). */
    record ContactEngagement(Long contactId, long opened, long clicked) {
    }
}
