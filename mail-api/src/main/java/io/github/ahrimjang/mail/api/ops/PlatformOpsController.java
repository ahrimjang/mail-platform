package io.github.ahrimjang.mail.api.ops;

import io.github.ahrimjang.mail.common.CampaignStatus;
import io.github.ahrimjang.mail.common.OpsActionRequest;
import io.github.ahrimjang.mail.common.OpsAuditEntry;
import io.github.ahrimjang.mail.common.OpsCampaignRow;
import io.github.ahrimjang.mail.common.OpsSignalsView;
import io.github.ahrimjang.mail.common.OpsWorkspaceDetail;
import io.github.ahrimjang.mail.common.OpsWorkspaceRow;
import io.github.ahrimjang.mail.core.service.ForbiddenException;
import io.github.ahrimjang.mail.core.service.PlatformOpsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 플랫폼 운영자 콘솔 API — 테넌트를 넘나든다. 권한 검사는 서비스가 매 호출 수행(403)하고,
 * 워크스페이스·캠페인은 경로 인자로 명시한다. 이 패키지 밖의 컨트롤러는 절대 이 서비스를
 * 쓰지 않는다 — 격리 예외가 번지지 않게 하는 경계다.
 */
@RestController
@RequestMapping("/api/ops")
public class PlatformOpsController {

    private final PlatformOpsService ops;

    public PlatformOpsController(PlatformOpsService ops) {
        this.ops = ops;
    }

    @GetMapping("/workspaces")
    public List<OpsWorkspaceRow> workspaces() {
        return ops.workspaces();
    }

    @GetMapping("/workspaces/{id}")
    public OpsWorkspaceDetail workspace(@PathVariable Long id) {
        return ops.workspace(id);
    }

    @PostMapping("/workspaces/{id}/suspend")
    public OpsWorkspaceRow suspend(@PathVariable Long id, @RequestBody OpsActionRequest r) {
        return ops.suspend(id, r.reason());
    }

    @PostMapping("/workspaces/{id}/unsuspend")
    public OpsWorkspaceRow unsuspend(@PathVariable Long id, @RequestBody OpsActionRequest r) {
        return ops.unsuspend(id, r.reason());
    }

    @PutMapping("/workspaces/{id}/plan")
    public OpsWorkspaceRow changePlan(@PathVariable Long id, @RequestBody OpsActionRequest r) {
        return ops.changePlan(id, r.plan(), r.reason());
    }

    @DeleteMapping("/workspaces/{id}/api-key")
    public OpsWorkspaceRow revokeApiKey(@PathVariable Long id, @RequestBody OpsActionRequest r) {
        return ops.revokeApiKey(id, r.reason());
    }

    @GetMapping("/campaigns")
    public List<OpsCampaignRow> campaigns(@RequestParam(required = false) CampaignStatus status,
                                          @RequestParam(required = false) Long workspaceId,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "50") int limit) {
        return ops.searchCampaigns(status, workspaceId, q, limit);
    }

    @PostMapping("/campaigns/{id}/abort")
    public OpsCampaignRow abort(@PathVariable Long id, @RequestBody OpsActionRequest r) {
        return ops.abortCampaign(id, r.reason());
    }

    @GetMapping("/signals")
    public OpsSignalsView signals() {
        return ops.signals();
    }

    @GetMapping("/audit")
    public List<OpsAuditEntry> audit(@RequestParam(defaultValue = "100") int limit) {
        return ops.audit(limit);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
