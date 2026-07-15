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
        jpa.completeIfSending(id, Instant.now());
    }

    @Override
    public void scheduleAbEvaluation(Long id, Instant evaluateAt) {
        jpa.scheduleAbEvaluation(id, evaluateAt);
    }

    @Override
    public List<Campaign> findDueForAbEvaluation(Instant now) {
        return jpa.findDueForAbEvaluation(now).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean claimAbWinner(Long id, String winner) {
        return jpa.claimAbWinner(id, winner) == 1;
    }

    private CampaignEntity toEntity(Campaign c) {
        return new CampaignEntity(c.getId(), c.getName(), c.getDescription(),
                c.getSubject(), c.getBody(), c.getStatus(), c.getCreatedAt(),
                c.getSenderName(), c.getSenderEmail(), c.getScheduledAt(), c.getEnqueuedAt(), c.getCompletedAt(),
                c.getTemplateId(), c.getListId(), c.getAbSubjectB(), c.getAbBodyB(), c.getAbSplitPercent(),
                c.getAbTestPercent(), c.getAbEvalMetric(), c.getAbEvalWaitMinutes(),
                c.getAbEvaluateAt(), c.getAbWinner());
    }

    private Campaign toDomain(CampaignEntity e) {
        Campaign c = new Campaign();
        c.setId(e.getId());
        c.setName(e.getName());
        c.setDescription(e.getDescription());
        c.setSubject(e.getSubject());
        c.setBody(e.getBody());
        c.setStatus(e.getStatus());
        c.setCreatedAt(e.getCreatedAt());
        c.setSenderName(e.getSenderName());
        c.setSenderEmail(e.getSenderEmail());
        c.setScheduledAt(e.getScheduledAt());
        c.setEnqueuedAt(e.getEnqueuedAt());
        c.setCompletedAt(e.getCompletedAt());
        c.setTemplateId(e.getTemplateId());
        c.setListId(e.getListId());
        c.setAbSubjectB(e.getAbSubjectB());
        c.setAbBodyB(e.getAbBodyB());
        c.setAbSplitPercent(e.getAbSplitPercent());
        c.setAbTestPercent(e.getAbTestPercent());
        c.setAbEvalMetric(e.getAbEvalMetric());
        c.setAbEvalWaitMinutes(e.getAbEvalWaitMinutes());
        c.setAbEvaluateAt(e.getAbEvaluateAt());
        c.setAbWinner(e.getAbWinner());
        return c;
    }
}
