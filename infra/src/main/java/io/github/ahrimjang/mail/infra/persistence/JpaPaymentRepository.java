package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.Payment;
import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.port.PaymentRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 어댑터: 결제 원장 포트의 JPA 구현. */
@Repository
public class JpaPaymentRepository implements PaymentRepository {

    private final PaymentJpaRepository jpa;

    public JpaPaymentRepository(PaymentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentEntity saved = jpa.save(new PaymentEntity(
                payment.getId(), payment.getWorkspaceId(), payment.getOrderId(),
                payment.getPlan().name(), payment.getAmountKrw(), payment.getStatus(),
                payment.getPaymentKey(), payment.getFailReason(), payment.getCreatedAt()));
        payment.setId(saved.getId());
        return payment;
    }

    @Override
    public List<Payment> findByWorkspace(Long workspaceId) {
        return jpa.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(e -> {
                    Payment p = "APPROVED".equals(e.getStatus())
                            ? Payment.approved(e.getWorkspaceId(), e.getOrderId(),
                                    Plan.valueOf(e.getPlan()), e.getAmountKrw(), e.getPaymentKey())
                            : Payment.failed(e.getWorkspaceId(), e.getOrderId(),
                                    Plan.valueOf(e.getPlan()), e.getAmountKrw(), e.getFailReason());
                    p.setId(e.getId());
                    p.setCreatedAt(e.getCreatedAt());
                    return p;
                })
                .toList();
    }
}
