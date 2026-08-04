package io.github.ahrimjang.mail.core.port;

import java.util.Set;

/**
 * 워크스페이스별 빌트인 템플릿 숨김 기록. 빌트인은 전역 자산이라 삭제 불가 —
 * 숨김은 그 워크스페이스의 목록에서만 빼는 표시용 기록이다.
 */
public interface BuiltinVisibilityRepository {

    /** 숨김 처리 — 이미 숨겨져 있으면 조용히 무시(멱등). */
    void hide(Long workspaceId, Long templateId);

    void unhide(Long workspaceId, Long templateId);

    Set<Long> hiddenTemplateIds(Long workspaceId);
}
