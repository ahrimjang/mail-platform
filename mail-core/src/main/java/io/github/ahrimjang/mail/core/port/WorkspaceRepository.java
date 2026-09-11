package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Workspace;

import java.util.Optional;

/** Persistence port for tenant workspaces. */
public interface WorkspaceRepository {

    Workspace save(Workspace workspace);

    Optional<Workspace> findById(Long id);

    /** 공개 구독 API 의 테넌트 역해석 — X-Api-Key → 워크스페이스. */
    Optional<Workspace> findByApiKey(String apiKey);

    /** 베타 가입 정원 판정용 — 전체 워크스페이스 수. */
    long count();

    /** 플랫폼 운영자 화면 — 전체 워크스페이스, 최근 가입순. 테넌트 격리의 의도적 예외. */
    java.util.List<Workspace> findAll();

    /** 플랫폼 운영자 신호 — {@code since} 이후 가입한 워크스페이스 수. */
    long countCreatedSince(java.time.Instant since);
}
