package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.PaymentView;
import io.github.ahrimjang.mail.core.domain.Payment;
import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.PaymentGateway;
import io.github.ahrimjang.mail.core.port.PaymentRepository;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 결제 유스케이스 — 카드 등록(빌링키 발급)과 플랜 변경.
 *
 * <p>정책(BILLING-policy 5절): 상향은 즉시 결제 후 즉시 적용, 하향은 새 플랜 한도를
 * 이미 초과 사용 중이면 불가. 결제 시도는 성공/실패 모두 {@code payments} 원장에
 * 남는다. 월 자동 갱신 결제(정기 청구 스케줄러)는 후속 — 지금은 플랜 변경 시점
 * 결제까지가 범위다.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final WorkspaceRepository workspaces;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final ContactRepository contacts;
    private final UserRepository users;
    private final WorkspaceContext ctx;

    public BillingService(WorkspaceRepository workspaces, PaymentRepository payments,
                          PaymentGateway gateway, ContactRepository contacts,
                          UserRepository users, WorkspaceContext ctx) {
        this.workspaces = workspaces;
        this.payments = payments;
        this.gateway = gateway;
        this.contacts = contacts;
        this.users = users;
        this.ctx = ctx;
    }

    /** PG 위젯에 넘길 고객 식별자 — 워크스페이스에 결정적으로 대응(별도 저장 불필요). */
    public String customerKey() {
        return "ws-" + ctx.currentWorkspaceId();
    }

    /** 카드 등록 완료(authKey)를 빌링키로 교환해 보관한다 (ADMIN). */
    public void registerCard(String authKey) {
        requireAdmin();
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalArgumentException("authKey is required");
        }
        Workspace workspace = requireWorkspace();
        String billingKey;
        try {
            billingKey = gateway.issueBillingKey(customerKey(), authKey);
        } catch (PaymentGateway.PaymentGatewayException e) {
            // PG 거절 사유를 409 로 표면화 — 감싸지 않으면 /error 재디스패치가
            // 시큐리티에 막혀 빈 401 로 둔갑한다
            throw new IllegalStateException("카드 등록에 실패했습니다: " + e.getMessage());
        }
        workspace.setBillingKey(billingKey);
        workspaces.save(workspace);
        log.info("빌링키 등록: workspace={}", workspace.getId());
    }

    /**
     * 플랜 변경. 상향이면 새 플랜 월정액을 즉시 청구하고(성공 시에만 적용),
     * 하향이면 무결제로 즉시 적용하되 새 한도를 이미 초과 사용 중이면 거부한다.
     *
     * @return 상향 결제의 영수증 한 줄. 하향(무결제)은 null.
     */
    public PaymentView changePlan(String planName) {
        requireAdmin();
        Plan target;
        try {
            target = Plan.valueOf(planName);
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown plan: " + planName);
        }
        if (target == Plan.ENTERPRISE) {
            throw new IllegalArgumentException("엔터프라이즈는 도입 문의로 진행됩니다.");
        }
        Workspace workspace = requireWorkspace();
        Plan current = workspace.getPlan();
        if (target == current) {
            throw new IllegalStateException("이미 " + target.name() + " 플랜을 사용 중입니다.");
        }

        if (target.ordinal() < current.ordinal()) {
            assertDowngradeFits(workspace, target);
            applyPlan(workspace, target);
            return null;
        }

        // 상향 — 결제 성공이 플랜 적용의 전제
        if (workspace.getBillingKey() == null) {
            throw new IllegalStateException("먼저 결제 카드를 등록해주세요.");
        }
        int amount = target.monthlyPriceKrw();
        String orderId = "ws%d-%s-%s".formatted(
                workspace.getId(), target.name(), UUID.randomUUID().toString().substring(0, 8));
        try {
            String paymentKey = gateway.chargeBilling(workspace.getBillingKey(), customerKey(),
                    amount, orderId, "Outpace " + target.name() + " 플랜");
            Payment receipt = payments.save(
                    Payment.approved(workspace.getId(), orderId, target, amount, paymentKey));
            applyPlan(workspace, target);
            log.info("플랜 상향 결제 승인: workspace={} plan={} amount={}", workspace.getId(), target, amount);
            return toView(receipt);
        } catch (PaymentGateway.PaymentGatewayException e) {
            payments.save(Payment.failed(workspace.getId(), orderId, target, amount, e.getMessage()));
            throw new IllegalStateException("결제에 실패했습니다: " + e.getMessage());
        }
    }

    /** 결제 이력 (ADMIN) — 성공/실패 전부, 최신부터. */
    public List<PaymentView> paymentHistory() {
        requireAdmin();
        return payments.findByWorkspace(ctx.currentWorkspaceId()).stream()
                .map(BillingService::toView)
                .toList();
    }

    /** 하향 대상 플랜의 한도를 이미 넘겨 쓰고 있으면 하향 불가 (BILLING-policy 5절). */
    private void assertDowngradeFits(Workspace workspace, Plan target) {
        if (target.contactLimit() != null
                && contacts.countByWorkspace(workspace.getId()) > target.contactLimit()) {
            throw new IllegalStateException(String.format(
                    "연락처가 %s 플랜 한도(%,d명)를 넘어 하향할 수 없어요. 먼저 연락처를 정리해주세요.",
                    target.name(), target.contactLimit()));
        }
        if (target.memberLimit() != null
                && users.countByWorkspaceId(workspace.getId()) > target.memberLimit()) {
            throw new IllegalStateException(String.format(
                    "멤버가 %s 플랜 한도(%d명)를 넘어 하향할 수 없어요. 먼저 멤버를 정리해주세요.",
                    target.name(), target.memberLimit()));
        }
    }

    private void applyPlan(Workspace workspace, Plan target) {
        workspace.setPlan(target);
        // 발송 속도 설정이 새 플랜 상한을 넘거나 미설정이면 상한으로 맞춘다
        Integer cap = target.sendRateCap();
        if (cap != null && (workspace.getSendRatePerSec() == null || workspace.getSendRatePerSec() > cap)) {
            workspace.setSendRatePerSec(cap);
        }
        workspaces.save(workspace);
    }

    private static PaymentView toView(Payment p) {
        return new PaymentView(p.getOrderId(), p.getPlan().name(), p.getAmountKrw(),
                p.getStatus(), p.getFailReason(), p.getCreatedAt());
    }

    private void requireAdmin() {
        if (!ctx.isAdmin()) {
            throw new ForbiddenException("workspace admin role required");
        }
    }

    private Workspace requireWorkspace() {
        return workspaces.findById(ctx.currentWorkspaceId())
                .orElseThrow(() -> new NoSuchElementException("workspace not found"));
    }
}
