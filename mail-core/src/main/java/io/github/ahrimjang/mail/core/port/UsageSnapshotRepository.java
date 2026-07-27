package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.UsageSnapshot;

import java.time.YearMonth;
import java.util.List;

/** 월 마감 사용량 스냅샷 저장소 포트. */
public interface UsageSnapshotRepository {

    /**
     * {@code month} 한 달치를 모든 워크스페이스에 대해 셋 기반으로 캡처한다.
     * 이미 캡처된 (워크스페이스, 월) 조합은 건드리지 않는다(멱등 — PK 충돌 무시).
     * 그 달이 끝나기 전에 만들어지지 않은 워크스페이스는 대상에서 제외.
     *
     * @return 새로 캡처된 행 수
     */
    int captureMonth(YearMonth month);

    /** 한 워크스페이스의 스냅샷 이력 — 최신 월부터. */
    List<UsageSnapshot> findByWorkspace(Long workspaceId);
}
