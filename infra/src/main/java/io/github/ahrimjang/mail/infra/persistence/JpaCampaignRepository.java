package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Adapter: implements the core {@link CampaignRepository} port over Spring Data JPA,
 * mapping between the domain model and the persistence entity.
 */
@Repository
public class JpaCampaignRepository implements CampaignRepository {

    private final CampaignJpaRepository jpa;

    public JpaCampaignRepository(CampaignJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Campaign save(Campaign campaign) {
        CampaignEntity saved = jpa.save(toEntity(campaign));
        return toDomain(saved);
    }

    @Override
    public Optional<Campaign> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public List<Campaign> findAll() {
        return jpa.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void updateStatus(Long id, CampaignStatus status) {
        jpa.findById(id).ifPresent(entity -> {
            entity.setStatus(status);
            jpa.save(entity);
        });
    }

    @Override
    public List<Campaign> findDueForEnqueue(Instant now) {
        return jpa.findDueForEnqueue(now).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean claimForEnqueue(Long id, Instant now) {
        return jpa.claimForEnqueue(id, now) == 1;
    }

    @Override
    public boolean claimForCancel(Long id) {
        return jpa.claimForCancel(id) == 1;
    }

    @Override
    public boolean claimForFanout(Long id) {
        return jpa.claimForFanout(id) == 1;
    }

    @Override
    public void markExpanded(Long id) {
        jpa.markExpanded(id);
    }

    @Override
    public boolean markSendingIfQueued(Long id) {
        return jpa.markSendingIfQueued(id) == 1;
    }

    @Override
    public void completeIfSending(Long id) {
        jpa.completeIfSending(id);
    }

    private CampaignEntity toEntity(Campaign c) {
        return new CampaignEntity(c.getId(), c.getSubject(), c.getBody(), c.getStatus(), c.getCreatedAt(),
                c.getSenderName(), c.getSenderEmail(), c.getScheduledAt(), c.getEnqueuedAt(),
                c.getTemplateId(), c.getListId());
    }

    private Campaign toDomain(CampaignEntity e) {
        Campaign c = new Campaign();
        c.setId(e.getId());
        c.setSubject(e.getSubject());
        c.setBody(e.getBody());
        c.setStatus(e.getStatus());
        c.setCreatedAt(e.getCreatedAt());
        c.setSenderName(e.getSenderName());
        c.setSenderEmail(e.getSenderEmail());
        c.setScheduledAt(e.getScheduledAt());
        c.setEnqueuedAt(e.getEnqueuedAt());
        c.setTemplateId(e.getTemplateId());
        c.setListId(e.getListId());
        return c;
    }
}
