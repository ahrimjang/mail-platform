package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;

/**
 * 결제 시도 한 건의 기록 — 성공/실패 모두 남는 매출 원장.
 * 순수 POJO. 상태는 APPROVED | FAILED 두 가지뿐(승인 전 중간 상태는
 * 즉시 결제 흐름이라 존재하지 않는다).
 */
public class Payment {

    private Long id;
    private Long workspaceId;
    private String orderId;
    private Plan plan;
    private int amountKrw;
    private String status;
    private String paymentKey;
    private String failReason;
    private Instant createdAt;

    public static Payment approved(Long workspaceId, String orderId, Plan plan, int amountKrw, String paymentKey) {
        Payment p = base(workspaceId, orderId, plan, amountKrw);
        p.status = "APPROVED";
        p.paymentKey = paymentKey;
        return p;
    }

    public static Payment failed(Long workspaceId, String orderId, Plan plan, int amountKrw, String failReason) {
        Payment p = base(workspaceId, orderId, plan, amountKrw);
        p.status = "FAILED";
        p.failReason = failReason;
        return p;
    }

    private static Payment base(Long workspaceId, String orderId, Plan plan, int amountKrw) {
        Payment p = new Payment();
        p.workspaceId = workspaceId;
        p.orderId = orderId;
        p.plan = plan;
        p.amountKrw = amountKrw;
        p.createdAt = Instant.now();
        return p;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getOrderId() { return orderId; }
    public Plan getPlan() { return plan; }
    public int getAmountKrw() { return amountKrw; }
    public String getStatus() { return status; }
    public String getPaymentKey() { return paymentKey; }
    public String getFailReason() { return failReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
