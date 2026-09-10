package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.SuppressionPageView;
import io.github.ahrimjang.mail.core.service.SuppressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 억제 목록(콘솔) — 이 워크스페이스가 더 이상 보내면 안 되는 주소 전체.
 * Bearer 필수(permitAll 아님). 테넌트는 서비스가 {@code WorkspaceContext} 로 해석한다.
 */
@RestController
@RequestMapping("/api/suppressions")
public class SuppressionController {

    private final SuppressionService suppressions;

    public SuppressionController(SuppressionService suppressions) {
        this.suppressions = suppressions;
    }

    @GetMapping
    public SuppressionPageView page(@RequestParam(defaultValue = "") String q,
                                    @RequestParam(required = false) String reason,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "25") int limit) {
        return suppressions.page(q.trim(), reason, offset, limit);
    }

    /** 이메일은 경로가 아니라 쿼리로 받는다 — '@'·'.' 이 경로 매칭에 걸리지 않게. */
    @DeleteMapping
    public ResponseEntity<Void> unsuppress(@RequestParam String email) {
        suppressions.unsuppress(email);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
