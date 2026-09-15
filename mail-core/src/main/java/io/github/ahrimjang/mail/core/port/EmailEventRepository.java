package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.common.EventType;
import io.github.ahrimjang.mail.core.domain.EmailEvent;

/**
 * Persistence port for recorded recipient engagement events (opens/clicks).
 */
public interface EmailEventRepository {

    /** Persist a newly observed engagement event. */
    void save(EmailEvent event);

    /** 캠페인에서 오픈(또는 클릭)한 메시지 수 — 프로젝션이 올린 카운터를 읽는다(V36). */
    long countDistinctMessages(Long campaignId, EventType type);

    /** A/B 한 안에서 오픈(또는 클릭)한 메시지 수 — 프로젝션이 올린 카운터를 읽는다(V36). */
    long countDistinctMessagesByVariant(Long campaignId, EventType type, String variant);

    /**
     * 참여 최초 기록 — 메시지×종류당 처음 한 번만 캠페인 카운터를 올린다. 반복 오픈과 Kafka 재전달은
     * 유니크 충돌로 걸러지고, 넣기와 +1 이 한 문장이라 둘 사이에 죽어도 어긋나지 않는다.
     * OPEN·CLICK 이 아니면 아무것도 하지 않는다.
     *
     * @return 이번 호출이 처음 기록했는지
     */
    boolean recordFirstEngagement(Long messageId, Long campaignId, EventType type, java.time.Instant occurredAt);

    /** 여러 캠페인의 참여 카운터 — 캠페인×A/B 안마다 한 줄. 참여가 없는 캠페인은 결과에 없다. */
    java.util.List<CampaignEngagement> engagementByCampaigns(java.util.Collection<Long> campaignIds);

    /** 캠페인×A/B 안의 카운터 한 줄 — {@code variant} null 이면 A/B 가 아닌 메시지. */
    record CampaignEngagement(Long campaignId, String variant, long opened, long clicked) {
    }

    /**
     * {@code since} 이후 이벤트 중 최초 기록이 빠진 것을 채운다(멱등). 배포 중 옛 워커가 카운터 없이
     * 이벤트만 쌓은 틈을 새 워커가 기동할 때 메운다.
     *
     * @return 카운터가 보정된 (캠페인, 안) 수
     */
    int reconcileEngagementSince(java.time.Instant since);

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
     * events point at) of one workspace since {@code since}; contacts without any
     * engagement are absent. 범위 조건이 없던 때는 전 테넌트 전량 집계였다(ARCH-11).
     */
    java.util.List<ContactEngagement> countEngagementByContact(Long workspaceId, java.time.Instant since);

    /** One contact's engagement counters (distinct messages per type). */
    record ContactEngagement(Long contactId, long opened, long clicked) {
    }
}
