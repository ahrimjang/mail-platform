package io.github.ahrimjang.mail.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workspaces")
public class WorkspaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** 요금 플랜 코드 (Plan enum 이름 — 한도는 코드가 소유). */
    @Column(nullable = false, length = 16)
    private String plan;

    /** Send throttle in msgs/sec; null = unlimited. */
    @Column
    private Integer sendRatePerSec;

    /** PG 빌링키 — 카드 등록 시 발급 (V23). */
    @Column(length = 255)
    private String billingKey;

    /** 외부 구독 연동 API 키 (V30, null = 미발급) */
    @Column(name = "api_key", length = 64)
    private String apiKey;

    @Column(nullable = false)
    private Instant createdAt;

    protected WorkspaceEntity() {
    }

    public WorkspaceEntity(Long id, String name, String plan, Integer sendRatePerSec,
                           String billingKey, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.plan = plan;
        this.sendRatePerSec = sendRatePerSec;
        this.billingKey = billingKey;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPlan() {
        return plan;
    }

    public Integer getSendRatePerSec() {
        return sendRatePerSec;
    }

    public String getBillingKey() {
        return billingKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
