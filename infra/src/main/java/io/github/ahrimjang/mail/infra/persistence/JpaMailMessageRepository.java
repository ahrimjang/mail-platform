package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Adapter: implements the core {@link MailMessageRepository} port (the send queue)
 * over Spring Data JPA.
 */
@Repository
public class JpaMailMessageRepository implements MailMessageRepository {

    private final MailMessageJpaRepository jpa;
    /** 스냅샷 테이블(V36)은 엔티티 없이 조건부 SQL 로만 다룬다 — upsert·version 조건이 JPQL 로는 안 된다. */
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public JpaMailMessageRepository(MailMessageJpaRepository jpa,
                                    org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
    }

    @Override
    public List<MailMessage> saveAll(List<MailMessage> messages) {
        return jpa.saveAll(messages.stream().map(this::toEntity).toList())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public MailMessage save(MailMessage message) {
        return toDomain(jpa.save(toEntity(message)));
    }

    @Override
    public Optional<MailMessage> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Instant> claim(Long messageId, Duration staleAfter) {
        // 마이크로초로 자른다 — timestamptz(6) 에 저장된 값과 finish 의 = 비교가 정확히 맞아야 한다
        Instant now = micros(Instant.now());
        return jpa.claimPending(messageId, now, now.minus(staleAfter)) == 1 ? Optional.of(now) : Optional.empty();
    }

    @Override
    public boolean finish(Long messageId, Instant claimedAt, io.github.ahrimjang.mail.common.MessageStatus status,
                          String errorMessage, Instant now) {
        return jpa.finish(messageId, micros(claimedAt), status, errorMessage, micros(now)) == 1;
    }

    @Override
    public boolean finishIfActive(Long messageId, io.github.ahrimjang.mail.common.MessageStatus status,
                                  String errorMessage, Instant now) {
        return jpa.finishIfActive(messageId, status, errorMessage, micros(now)) == 1;
    }

    @Override
    public boolean markBounced(Long messageId, String reason, Instant now) {
        return jpa.markBouncedIfDelivered(messageId, reason, micros(now)) == 1;
    }

    private static Instant micros(Instant t) {
        return t.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    @Override
    public Optional<MailMessage> findByUnsubToken(String token) {
        return jpa.findByUnsubToken(token).map(this::toDomain);
    }

    @Override
    public Optional<MailMessage> findByTrackingToken(String token) {
        return jpa.findByTrackingToken(token).map(this::toDomain);
    }

    @Override
    public List<Long> findPendingIdsByCampaign(Long campaignId) {
        return jpa.findPendingIdsByCampaignId(campaignId);
    }

    @Override
    public List<Long> findPendingTestIdsByCampaign(Long campaignId) {
        return jpa.findPendingTestIdsByCampaignId(campaignId);
    }

    @Override
    public List<Long> findPendingHeldIdsByCampaign(Long campaignId) {
        return jpa.findPendingHeldIdsByCampaignId(campaignId);
    }

    @Override
    public boolean hasUnfinishedTestBatch(Long campaignId) {
        return jpa.existsUnfinishedTestBatch(campaignId);
    }

    @Override
    public List<Long> findStaleIds(Instant cutoff, int limit) {
        return jpa.findStaleIds(cutoff, org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
    }

    @Override
    public int touchPending(List<Long> ids, Instant now) {
        return ids.isEmpty() ? 0 : jpa.touchPending(ids, now);
    }

    @Override
    public Long maxContactIdByCampaign(Long campaignId) {
        return jpa.maxContactIdByCampaignId(campaignId);
    }

    @Override
    public int cancelPendingByCampaign(Long campaignId) {
        return jpa.cancelPendingByCampaignId(campaignId, Instant.now());
    }

    @Override
    public List<MailMessage> findRecentByCampaign(Long campaignId, int limit) {
        return jpa.findByCampaignIdOrderByUpdatedAtDescIdDesc(
                        campaignId, org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<MailMessage> findRecentByContact(Long contactId, int limit) {
        return jpa.findRecentByContact(contactId, org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countSentByWorkspaceSince(Long workspaceId, java.time.Instant since) {
        return jpa.countSentByWorkspaceSince(workspaceId, since);
    }

    @Override
    public List<ContactSentCount> countSentByContact(Long workspaceId, Instant since) {
        return jpa.countSentByContact(workspaceId, since).stream()
                .map(row -> new ContactSentCount((Long) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    @Override
    public List<SendLogBucket> aggregateLogByCampaign(Long campaignId, int bucketSeconds, int limit) {
        return jpa.aggregateLogByCampaign(campaignId, bucketSeconds, limit).stream()
                .map(row -> new SendLogBucket(
                        Instant.ofEpochSecond(((Number) row[0]).longValue() * bucketSeconds),
                        MessageStatus.valueOf((String) row[1]),
                        ((Number) row[2]).longValue(),
                        (String) row[3]))
                .toList();
    }

    @Override
    public List<DailyOutcome> aggregateDailyOutcomes(Long workspaceId, Instant since, java.time.ZoneId zone) {
        return jpa.aggregateDailyOutcomes(workspaceId, since, zone.getId()).stream()
                .map(row -> new DailyOutcome(
                        ((java.sql.Date) row[0]).toLocalDate(),
                        MessageStatus.valueOf((String) row[1]),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    public boolean hasPendingOrSending(Long campaignId) {
        return jpa.existsByCampaignIdAndStatusIn(campaignId,
                java.util.List.of(MessageStatus.PENDING, MessageStatus.SENDING));
    }

    @Override
    public MessageCounts countByCampaign(Long campaignId) {
        return countByCampaigns(List.of(campaignId)).getOrDefault(campaignId, MessageCounts.EMPTY);
    }

    @Override
    public java.util.Map<Long, MessageCounts> countByCampaigns(java.util.Collection<Long> campaignIds) {
        if (campaignIds.isEmpty()) {
            return java.util.Map.of();
        }
        // 상태마다 따로 세던 COUNT 7개를 GROUP BY 한 번으로 — (campaign_id, status) 인덱스를 탄다
        java.util.Map<Long, long[]> acc = new java.util.HashMap<>(); // [total, pending, sending, sent, failed, bounced, suppressed]
        for (Object[] row : jpa.countByCampaignIdsGroupByStatus(campaignIds)) {
            long[] a = acc.computeIfAbsent((Long) row[0], k -> new long[7]);
            long n = ((Number) row[2]).longValue();
            a[0] += n;
            switch ((MessageStatus) row[1]) {
                case PENDING -> a[1] += n;
                case SENDING -> a[2] += n;
                case SENT -> a[3] += n;
                case FAILED -> a[4] += n;
                case BOUNCED -> a[5] += n;
                case SUPPRESSED -> a[6] += n;
                default -> { } // CANCELED 은 total 에만 들어간다 (이전과 같음)
            }
        }
        java.util.Map<Long, MessageCounts> out = new java.util.HashMap<>();
        acc.forEach((id, a) -> out.put(id, new MessageCounts(a[0], a[1], a[2], a[3], a[4], a[5], a[6])));
        return out;
    }

    @Override
    public java.util.Map<Long, CountSnapshot> findCountSnapshots(java.util.Collection<Long> campaignIds) {
        if (campaignIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, CountSnapshot> out = new java.util.HashMap<>();
        jdbc.query("""
                select campaign_id, version, valid, total, pending, sending, sent, failed, bounced, suppressed
                from campaign_delivery_snapshots where campaign_id in (:ids)
                """, java.util.Map.of("ids", campaignIds), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            MessageCounts counts = rs.getBoolean("valid")
                    ? new MessageCounts(rs.getLong("total"), rs.getLong("pending"), rs.getLong("sending"),
                            rs.getLong("sent"), rs.getLong("failed"), rs.getLong("bounced"), rs.getLong("suppressed"))
                    : null;
            out.put(rs.getLong("campaign_id"), new CountSnapshot(rs.getLong("version"), counts));
        });
        return out;
    }

    @Override
    public boolean saveCountSnapshot(Long campaignId, Long expectedVersion, MessageCounts c, Instant capturedAt) {
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", campaignId)
                .addValue("total", c.total()).addValue("pending", c.pending()).addValue("sending", c.sending())
                .addValue("sent", c.sent()).addValue("failed", c.failed()).addValue("bounced", c.bounced())
                .addValue("suppressed", c.suppressed())
                .addValue("at", java.time.OffsetDateTime.ofInstant(capturedAt, java.time.ZoneOffset.UTC))
                .addValue("version", expectedVersion);
        String sql = expectedVersion == null
                // 행이 없을 때만 — 그 사이 무효화가 행을 만들었으면 충돌로 아무것도 하지 않는다
                ? """
                  insert into campaign_delivery_snapshots
                      (campaign_id, version, valid, total, pending, sending, sent, failed, bounced, suppressed, captured_at)
                  values (:id, 0, true, :total, :pending, :sending, :sent, :failed, :bounced, :suppressed, :at)
                  on conflict (campaign_id) do nothing
                  """
                // 읽을 때의 version 그대로일 때만 — 세는 동안 무효화가 끼었으면 0행
                : """
                  update campaign_delivery_snapshots
                  set valid = true, total = :total, pending = :pending, sending = :sending, sent = :sent,
                      failed = :failed, bounced = :bounced, suppressed = :suppressed, captured_at = :at
                  where campaign_id = :id and version = :version
                  """;
        return jdbc.update(sql, params) == 1;
    }

    @Override
    public void evictCountSnapshot(Long campaignId) {
        jdbc.update("""
                insert into campaign_delivery_snapshots (campaign_id, version, valid) values (:id, 1, false)
                on conflict (campaign_id) do update
                    set version = campaign_delivery_snapshots.version + 1, valid = false
                """, java.util.Map.of("id", campaignId));
    }

    @Override
    public List<VariantDelivery> countByCampaignAndVariant(Long campaignId) {
        return jpa.countByCampaignIdGroupByVariant(campaignId).stream()
                .map(row -> new VariantDelivery(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        row[2] == null ? 0L : ((Number) row[2]).longValue()))
                .toList();
    }

    private MailMessageEntity toEntity(MailMessage m) {
        return new MailMessageEntity(
                m.getId(), m.getCampaignId(), m.getRecipient(), m.getStatus(),
                m.getAttempts(), m.getErrorMessage(), m.getUpdatedAt(), m.getUnsubToken(),
                m.getTrackingToken(), m.getContactId(), m.getVariant());
    }

    private MailMessage toDomain(MailMessageEntity e) {
        MailMessage m = new MailMessage();
        m.setId(e.getId());
        m.setCampaignId(e.getCampaignId());
        m.setRecipient(e.getRecipient());
        m.setStatus(e.getStatus());
        m.setAttempts(e.getAttempts());
        m.setErrorMessage(e.getErrorMessage());
        m.setUpdatedAt(e.getUpdatedAt());
        m.setUnsubToken(e.getUnsubToken());
        m.setTrackingToken(e.getTrackingToken());
        m.setContactId(e.getContactId());
        m.setVariant(e.getVariant());
        return m;
    }

    @Override
    public WorkspaceBounceStats workspaceBounceStats(Long workspaceId, java.time.Instant since) {
        Object[] row = jpa.workspaceBounceStats(workspaceId, since).get(0);
        return new WorkspaceBounceStats(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    @Override
    public List<WorkspaceStatusCount> aggregateByWorkspaceSince(java.time.Instant since) {
        return jpa.aggregateByWorkspaceSince(since).stream()
                .map(row -> new WorkspaceStatusCount(
                        ((Number) row[0]).longValue(),
                        (MessageStatus) row[1],
                        ((Number) row[2]).longValue(),
                        (Instant) row[3]))
                .toList();
    }

    @Override
    public long countByStatusSince(MessageStatus status, java.time.Instant since) {
        return jpa.countByStatusAndUpdatedAtGreaterThanEqual(status, since);
    }
}
