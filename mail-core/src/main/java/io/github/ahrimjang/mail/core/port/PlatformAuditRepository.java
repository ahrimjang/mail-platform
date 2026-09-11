package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.PlatformAuditEntry;

import java.util.List;

/** 플랫폼 운영자 감사 로그 저장소 포트. */
public interface PlatformAuditRepository {

    PlatformAuditEntry save(PlatformAuditEntry entry);

    /** 최신순 최대 limit 건. */
    List<PlatformAuditEntry> findRecent(int limit);
}
