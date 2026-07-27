package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Payment;

import java.util.List;

/** 결제 원장 저장소 포트. */
public interface PaymentRepository {

    Payment save(Payment payment);

    /** 워크스페이스의 결제 이력 — 최신부터. */
    List<Payment> findByWorkspace(Long workspaceId);
}
