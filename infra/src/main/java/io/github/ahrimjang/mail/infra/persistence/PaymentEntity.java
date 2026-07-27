package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 결제 원장 한 줄 — 성공/실패 모두 기록 (V23). */
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    @Column(nullable = false, length = 64, unique = true)
    private String orderId;

    @Column(nullable = false, length = 16)
    private String plan;

    @Column(nullable = false)
    private int amountKrw;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 64)
    private String paymentKey;

    @Column(columnDefinition = "text")
    private String failReason;

    @Column(nullable = false)
    private Instant createdAt;

    protected PaymentEntity() {
    }

    public PaymentEntity(Long id, Long workspaceId, String orderId, String plan, int amountKrw,
                         String status, String paymentKey, String failReason, Instant createdAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.orderId = orderId;
        this.plan = plan;
        this.amountKrw = amountKrw;
        this.status = status;
        this.paymentKey = paymentKey;
        this.failReason = failReason;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getOrderId() { return orderId; }
    public String getPlan() { return plan; }
    public int getAmountKrw() { return amountKrw; }
    public String getStatus() { return status; }
    public String getPaymentKey() { return paymentKey; }
    public String getFailReason() { return failReason; }
    public Instant getCreatedAt() { return createdAt; }
}
