package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.PlatformAuditEntry;
import io.github.ahrimjang.mail.core.port.PlatformAuditRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 어댑터: 플랫폼 운영자 감사 로그 포트의 JPA 구현. */
@Repository
public class JpaPlatformAuditRepository implements PlatformAuditRepository {

    private final PlatformAuditJpaRepository jpa;

    public JpaPlatformAuditRepository(PlatformAuditJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PlatformAuditEntry save(PlatformAuditEntry e) {
        PlatformAuditEntity saved = jpa.save(new PlatformAuditEntity(e.getId(), e.getActorEmail(), e.getAction(),
                e.getWorkspaceId(), e.getCampaignId(), e.getDetail(), e.getCreatedAt()));
        e.setId(saved.getId());
        return e;
    }

    @Override
    public List<PlatformAuditEntry> findRecent(int limit) {
        return jpa.findRecent(PageRequest.of(0, limit)).stream().map(JpaPlatformAuditRepository::toDomain).toList();
    }

    private static PlatformAuditEntry toDomain(PlatformAuditEntity e) {
        PlatformAuditEntry d = new PlatformAuditEntry();
        d.setId(e.getId());
        d.setActorEmail(e.getActorEmail());
        d.setAction(e.getAction());
        d.setWorkspaceId(e.getWorkspaceId());
        d.setCampaignId(e.getCampaignId());
        d.setDetail(e.getDetail());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
