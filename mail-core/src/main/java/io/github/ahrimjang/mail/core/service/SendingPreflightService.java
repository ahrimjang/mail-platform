package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.SendingPreflightView;
import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.springframework.stereotype.Service;

/**
 * 캠페인 등록 게이트를 <b>작성 전에</b> 조회하는 창구.
 *
 * <p>집행하는 게이트({@link CampaignService#create})와 같은 재료를 읽기 전용으로 모아
 * 준다. 목적은 실패 시점을 앞당기는 것뿐이다 — 여기서 통과로 보였다고 등록이 보장되지는
 * 않으며(그 사이 한도가 차거나 정지될 수 있다), 방어는 여전히 등록 시점의 게이트다.
 */
@Service
public class SendingPreflightService {

    private final WorkspaceContext ctx;
    private final EmailVerificationService verification;
    private final SendingSuspensionService suspension;
    private final SenderPolicy senderPolicy;
    private final SendingWarmupService warmup;
    private final PlanLimits planLimits;

    public SendingPreflightService(WorkspaceContext ctx, EmailVerificationService verification,
                                  SendingSuspensionService suspension, SenderPolicy senderPolicy,
                                  SendingWarmupService warmup, PlanLimits planLimits) {
        this.ctx = ctx;
        this.verification = verification;
        this.suspension = suspension;
        this.senderPolicy = senderPolicy;
        this.warmup = warmup;
        this.planLimits = planLimits;
    }

    public SendingPreflightView current() {
        Long workspaceId = ctx.currentWorkspaceId();
        String suspensionReason = suspension.suspensionReasonOf(workspaceId).orElse(null);
        SendingWarmupService.Status warmupStatus = warmup.statusOf(workspaceId);
        Plan plan = planLimits.planOf(workspaceId);
        return new SendingPreflightView(
                verification.currentUserVerified(),
                suspensionReason != null,
                suspensionReason,
                senderPolicy.senderDomain(),
                warmupStatus.active(),
                warmupStatus.active() ? warmupStatus.batchLimit() : null,
                warmupStatus.active() ? warmupStatus.sentRemaining() : null,
                plan.name(),
                plan.monthlySendLimit(),
                planLimits.monthlySent(workspaceId));
    }
}
