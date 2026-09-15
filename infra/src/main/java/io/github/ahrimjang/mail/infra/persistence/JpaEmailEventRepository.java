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
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public JpaEmailEventRepository(EmailEventJpaRepository jpa,
                                   org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
    }

    @Override
    public void save(EmailEvent e) {
        jpa.save(new EmailEventEntity(
                null, e.getMessageId(), e.getCampaignId(), e.getType(), e.getUrl(), e.getOccurredAt()));
    }

    @Override
    public long countDistinctMessages(Long campaignId, EventType type) {
        // 이벤트 전량 count(distinct) 대신 프로젝션이 올린 카운터(V36)의 합 — A/B 안별 줄을 더한다
        Long n = jdbc.queryForObject("select coalesce(sum(" + counterColumn(type) + "), 0) "
                        + "from campaign_engagement_counts where campaign_id = :id",
                java.util.Map.of("id", campaignId), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public long countDistinctMessagesByVariant(Long campaignId, EventType type, String variant) {
        Long n = jdbc.queryForObject("select coalesce(sum(" + counterColumn(type) + "), 0) "
                        + "from campaign_engagement_counts where campaign_id = :id and variant_key = :variant",
                java.util.Map.of("id", campaignId, "variant", variant == null ? "-" : variant), Long.class);
        return n == null ? 0 : n;
    }

    /** 카운터 열 이름 — 열거형에서만 고르므로 SQL 에 이어 붙여도 주입될 여지가 없다. */
    private static String counterColumn(EventType type) {
        return switch (type) {
            case OPEN -> "opened";
            case CLICK -> "clicked";
            default -> throw new IllegalArgumentException("참여 카운터가 없는 이벤트 종류: " + type);
        };
    }

    @Override
    public boolean recordFirstEngagement(Long messageId, Long campaignId, EventType type, java.time.Instant occurredAt) {
        if (type != EventType.OPEN && type != EventType.CLICK) {
            return false;
        }
        // 넣기와 +1 을 한 문장으로: 유니크 충돌(반복 오픈·재전달)이면 ins 가 비어 카운터도 안 오른다.
        // 같은 참여가 동시에 두 번 와도 PK 가 하나만 통과시킨다 — claim 과 같은 원리.
        int rows = jdbc.update("""
                with ins as (
                    insert into message_engagements (message_id, type, campaign_id, variant, first_at)
                    values (:messageId, :type, :campaignId,
                            (select m.variant from mail_messages m where m.id = :messageId), :at)
                    on conflict (message_id, type) do nothing
                    returning campaign_id, variant, type
                )
                insert into campaign_engagement_counts (campaign_id, variant_key, opened, clicked)
                select campaign_id, coalesce(variant, '-'),
                       case when type = 'OPEN' then 1 else 0 end,
                       case when type = 'CLICK' then 1 else 0 end
                from ins
                on conflict (campaign_id, variant_key) do update
                    set opened = campaign_engagement_counts.opened + excluded.opened,
                        clicked = campaign_engagement_counts.clicked + excluded.clicked
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("messageId", messageId)
                .addValue("type", type.name())
                .addValue("campaignId", campaignId)
                .addValue("at", java.time.OffsetDateTime.ofInstant(occurredAt, java.time.ZoneOffset.UTC)));
        return rows > 0;
    }

    @Override
    public java.util.List<CampaignEngagement> engagementByCampaigns(java.util.Collection<Long> campaignIds) {
        if (campaignIds.isEmpty()) {
            return java.util.List.of();
        }
        return jdbc.query("""
                select campaign_id, variant_key, opened, clicked
                from campaign_engagement_counts where campaign_id in (:ids)
                """, java.util.Map.of("ids", campaignIds), (rs, i) -> new CampaignEngagement(
                rs.getLong("campaign_id"),
                "-".equals(rs.getString("variant_key")) ? null : rs.getString("variant_key"),
                rs.getLong("opened"),
                rs.getLong("clicked")));
    }

    @Override
    public int reconcileEngagementSince(java.time.Instant since) {
        // 실시간 경로와 같은 "넣은 것만 센다" 문장을 기간 단위로. 이미 기록된 참여는 충돌로 빠진다.
        return jdbc.update("""
                with ins as (
                    insert into message_engagements (message_id, type, campaign_id, variant, first_at)
                    select e.message_id, e.type, e.campaign_id, m.variant, min(e.occurred_at)
                    from email_events e
                    left join mail_messages m on m.id = e.message_id
                    where e.type in ('OPEN', 'CLICK') and e.occurred_at >= :since
                    group by e.message_id, e.type, e.campaign_id, m.variant
                    on conflict (message_id, type) do nothing
                    returning campaign_id, variant, type
                )
                insert into campaign_engagement_counts (campaign_id, variant_key, opened, clicked)
                select campaign_id, coalesce(variant, '-'),
                       count(*) filter (where type = 'OPEN'),
                       count(*) filter (where type = 'CLICK')
                from ins
                group by campaign_id, coalesce(variant, '-')
                on conflict (campaign_id, variant_key) do update
                    set opened = campaign_engagement_counts.opened + excluded.opened,
                        clicked = campaign_engagement_counts.clicked + excluded.clicked
                """, java.util.Map.of("since", java.time.OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC)));
    }

    @Override
    public java.util.List<DailyEngagement> aggregateDailyEngagement(Long workspaceId, java.time.Instant since, java.time.ZoneId zone) {
        return jpa.aggregateDailyEngagement(workspaceId, since, zone.getId()).stream()
                .map(row -> new DailyEngagement(
                        ((java.sql.Date) row[0]).toLocalDate(),
                        EventType.valueOf((String) row[1]),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    public java.util.List<LinkClicks> topClickedLinks(Long workspaceId, java.time.Instant since, int limit) {
        return jpa.topClickedLinks(workspaceId, since, org.springframework.data.domain.PageRequest.of(0, limit)).stream()
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
    public java.util.List<HeatmapCell> aggregateOpenHeatmap(Long workspaceId, java.time.Instant since, java.time.ZoneId zone) {
        return jpa.aggregateOpenHeatmap(workspaceId, since, zone.getId()).stream()
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
    public java.util.List<ContactEngagement> countEngagementByContact(Long workspaceId, java.time.Instant since) {
        // Rows come as (contactId, type, count) — fold the two event types into
        // one record per contact.
        java.util.Map<Long, long[]> byContact = new java.util.LinkedHashMap<>();
        for (Object[] row : jpa.countEngagementByContact(workspaceId, since)) {
            long[] counts = byContact.computeIfAbsent((Long) row[0], k -> new long[2]);
            counts[row[1] == EventType.OPEN ? 0 : 1] = ((Number) row[2]).longValue();
        }
        return byContact.entrySet().stream()
                .map(e -> new ContactEngagement(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }
}
