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
    public List<Campaign> findByWorkspace(Long workspaceId) {
        return jpa.findByWorkspaceId(workspaceId).stream().map(this::toDomain).toList();
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
        return jpa.claimForFanout(id, Instant.now()) == 1;
    }

    @Override
    public boolean abort(Long id) {
        return jpa.abort(id, Instant.now()) == 1;
    }

    @Override
    public List<Campaign> findStuckExpanding(Instant cutoff) {
        return jpa.findStuckExpanding(cutoff).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean resetExpandingToQueued(Long id, Instant cutoff) {
        return jpa.resetExpandingToQueued(id, cutoff) == 1;
    }

    @Override
    public List<Campaign> findOrphanQueued(Instant cutoff) {
        return jpa.findOrphanQueued(cutoff).stream().map(this::toDomain).toList();
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
    public boolean completeIfSending(Long id) {
        return jpa.completeIfSending(id, Instant.now()) > 0;
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

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }

    @Override
    public List<Campaign> search(CampaignStatus status, Long workspaceId, String q, int limit) {
        // 조건이 전부 선택이라 JPQL 의 ":x is null or" 대신 Specification 으로 조립한다 —
        // enum/nullable 파라미터의 타입 추론 문제를 피하고 인덱스도 조건이 있을 때만 탄다.
        org.springframework.data.jpa.domain.Specification<CampaignEntity> spec = (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> where = new java.util.ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (workspaceId != null) {
                where.add(cb.equal(root.get("workspaceId"), workspaceId));
            }
            if (q != null && !q.isBlank()) {
                String needle = "%" + q.trim().toLowerCase() + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("name")), needle),
                        cb.like(cb.lower(root.get("subject")), needle),
                        cb.like(cb.lower(root.get("createdBy")), needle)));
            }
            return cb.and(where.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        return jpa.findAll(spec, page).getContent().stream().map(this::toDomain).toList();
    }

    @Override
    public List<Campaign> findInFlight() {
        return jpa.findInFlight().stream().map(this::toDomain).toList();
    }

    @Override
    public long countAbortedSince(Instant since) {
        return jpa.countByStatusAndCompletedAtGreaterThanEqual(CampaignStatus.CANCELED, since);
    }

    private CampaignEntity toEntity(Campaign c) {
        CampaignEntity entity = new CampaignEntity(c.getId(), c.getName(), c.getDescription(),
                c.getSubject(), c.getBody(), c.getStatus(), c.getCreatedAt(),
                c.getSenderName(), c.getSenderEmail(), c.getScheduledAt(), c.getEnqueuedAt(), c.getCompletedAt(),
                c.getEndsAt(), c.getDraftRecipients(), c.getTemplateId(), c.getListId(),
                c.getSegMinOpenPercent(), c.getSegMinClickPercent(),
                c.getAbSubjectB(), c.getAbBodyB(), c.getAbSplitPercent(),
                c.getAbTestPercent(), c.getAbEvalMetric(), c.getAbEvalWaitMinutes(),
                c.getAbEvaluateAt(), c.getAbWinner());
        entity.setWorkspaceId(c.getWorkspaceId());
        entity.setCreatedBy(c.getCreatedBy());
        entity.setEmailId(c.getEmailId());
        entity.setReplyTo(c.getReplyTo());
        return entity;
    }

    private Campaign toDomain(CampaignEntity e) {
        Campaign c = new Campaign();
        c.setId(e.getId());
        c.setWorkspaceId(e.getWorkspaceId());
        c.setCreatedBy(e.getCreatedBy());
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
        c.setEndsAt(e.getEndsAt());
        c.setDraftRecipients(e.getDraftRecipients());
        c.setTemplateId(e.getTemplateId());
        c.setEmailId(e.getEmailId());
        c.setReplyTo(e.getReplyTo());
        c.setListId(e.getListId());
        c.setSegMinOpenPercent(e.getSegMinOpenPercent());
        c.setSegMinClickPercent(e.getSegMinClickPercent());
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
