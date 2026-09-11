package io.github.ahrimjang.mail.api.ops;

import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 현재 사용자의 확장 권한 — 콘솔 셸이 "운영" 메뉴를 보일지 정하는 데 쓴다. 숨김은 편의일
 * 뿐이고 실제 게이트는 서비스의 403 이다. 로그인 응답에 넣지 않고 따로 두는 이유:
 * 권한 부여가 재로그인 없이 다음 화면 이동에서 바로 반영되게.
 */
@RestController
public class AccessController {

    private final WorkspaceContext ctx;

    public AccessController(WorkspaceContext ctx) {
        this.ctx = ctx;
    }

    @GetMapping("/api/me/access")
    public Map<String, Boolean> access() {
        return Map.of("platformOperator", ctx.isPlatformOperator());
    }
}
