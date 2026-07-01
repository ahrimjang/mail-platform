package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.MessageStatus;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

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
    public void saveAll(List<MailMessage> messages) {
        jpa.saveAll(messages.stream().map(this::toEntity).toList());
    }

    @Override
    public MailMessage save(MailMessage message) {
        return toDomain(jpa.save(toEntity(message)));
    }

    @Override
    public List<MailMessage> findPending(int limit) {
        return jpa.findByStatusOrderByIdAsc(MessageStatus.PENDING, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
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
    public MessageCounts countByCampaign(Long campaignId) {
        long total = jpa.countByCampaignId(campaignId);
        long pending = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.PENDING);
        long sent = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.SENT);
        long failed = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.FAILED);
        long bounced = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.BOUNCED);
        long suppressed = jpa.countByCampaignIdAndStatus(campaignId, MessageStatus.SUPPRESSED);
        return new MessageCounts(total, pending, sent, failed, bounced, suppressed);
    }

    private MailMessageEntity toEntity(MailMessage m) {
        return new MailMessageEntity(
                m.getId(), m.getCampaignId(), m.getRecipient(), m.getStatus(),
                m.getAttempts(), m.getErrorMessage(), m.getUpdatedAt(), m.getUnsubToken(),
                m.getTrackingToken());
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
        return m;
    }
}
