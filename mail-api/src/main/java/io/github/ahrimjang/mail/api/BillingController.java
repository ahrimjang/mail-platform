package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.BillingConfigView;
import io.github.ahrimjang.mail.common.ChangePlanRequest;
import io.github.ahrimjang.mail.common.PaymentView;
import io.github.ahrimjang.mail.common.RegisterCardRequest;
import io.github.ahrimjang.mail.core.service.BillingService;
import io.github.ahrimjang.mail.core.service.ForbiddenException;
import io.github.ahrimjang.mail.core.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 결제 API — 카드 등록·플랜 변경·결제 이력. 전부 인증 필수(ADMIN 은 서비스가 검사). */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingService billing;
    private final WorkspaceService workspace;
    private final String clientKey;

    public BillingController(BillingService billing, WorkspaceService workspace,
                             @Value("${app.toss.client-key:test_ck_docs_Ovk5rk1EwkEbP0W43n07xlzm}") String clientKey) {
        this.billing = billing;
        this.workspace = workspace;
        this.clientKey = clientKey;
    }

    /** 결제 위젯 초기화 설정 (공개 키 — 시크릿 키는 절대 안 나감). */
    @GetMapping("/config")
    public BillingConfigView config() {
        return new BillingConfigView(clientKey, billing.customerKey(),
                workspace.current().billingRegistered());
    }

    /** 카드 등록 완료 — 위젯 successUrl 이 넘겨준 authKey 를 빌링키로 교환. */
    @PostMapping("/card")
    public ResponseEntity<Void> registerCard(@RequestBody RegisterCardRequest request) {
        billing.registerCard(request.authKey());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** 플랜 변경 — 상향은 즉시 결제(영수증 반환), 하향은 무결제(본문 없음). */
    @PostMapping("/plan")
    public ResponseEntity<PaymentView> changePlan(@RequestBody ChangePlanRequest request) {
        PaymentView receipt = billing.changePlan(request.plan());
        return receipt == null ? ResponseEntity.ok().build() : ResponseEntity.ok(receipt);
    }

    /** 결제 이력 — 성공/실패 전부. */
    @GetMapping("/payments")
    public List<PaymentView> payments() {
        return billing.paymentHistory();
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
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
