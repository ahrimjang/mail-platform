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

    public JpaMailMessageRepository(MailMessageJpaRepository jpa) {
        this.jpa = jpa;
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
    public boolean claim(Long messageId, Duration staleAfter) {
        Instant now = Instant.now();
        return jpa.claimPending(messageId, now, now.minus(staleAfter)) == 1;
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
    public List<ContactSentCount> countSentByContact() {
        return jpa.countSentByContact().stream()
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
        long total = jpa.countByCampaignId(campaignId);
        long pending = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.PENDING);
        long sending = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.SENDING);
        long sent = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.SENT);
        long failed = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.FAILED);
        long bounced = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.BOUNCED);
        long suppressed = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.SUPPRESSED);
        return new MessageCounts(total, pending, sending, sent, failed, bounced, suppressed);
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
}
