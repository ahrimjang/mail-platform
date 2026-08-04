package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.NoSuchElementException;

/**
 * 플랜 한도 집행의 단일 창구 — 정책 원문은 docs/BILLING-policy.md.
 *
 * <p>집행 지점 원칙: 발송 중 컷오프 금지. 월 발송량 한도는 **캠페인 등록 시**만
 * 검사하고, 진행 중인 캠페인은 끝까지 나간다(디스패치 핫패스에 쿼터 조회를 얹지
 * 않는 이유이기도 하다). 연락처·멤버는 추가 시점, 발송 속도는 설정 저장 시점.
 */
@Service
public class PlanLimits {

    private final WorkspaceRepository workspaces;
    private final MailMessageRepository messages;
    private final ContactRepository contacts;
    private final UserRepository users;

    public PlanLimits(WorkspaceRepository workspaces, MailMessageRepository messages,
                      ContactRepository contacts, UserRepository users) {
        this.workspaces = workspaces;
        this.messages = messages;
        this.contacts = contacts;
        this.users = users;
    }

    /** 이번 달(로컬 기준 1일~) 발송 성공 수 — 청구·한도 공용 수치. */
    public long monthlySent(Long workspaceId) {
        ZoneId zone = ZoneId.systemDefault();
        Instant monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        return messages.countSentByWorkspaceSince(workspaceId, monthStart);
    }

    /** 캠페인 등록 가능 여부 — 월 발송량 한도 도달 시 차단. */
    public void assertCampaignRegistrationAllowed(Long workspaceId) {
        Plan plan = planOf(workspaceId);
        if (plan.monthlySendLimit() == null) {
            return;
        }
        long sent = monthlySent(workspaceId);
        if (sent >= plan.monthlySendLimit()) {
            throw new PlanLimitExceededException(String.format(
                    "이번 달 발송 한도(%,d통)에 도달했습니다. 플랜을 올리면 바로 이어서 보낼 수 있어요.",
                    plan.monthlySendLimit()));
        }
    }

    /** 연락처 {@code adding}건 추가 가능 여부 (개별 등록·CSV 임포트 공용). */
    public void assertContactsAddable(Long workspaceId, long adding) {
        Plan plan = planOf(workspaceId);
        if (plan.contactLimit() == null) {
            return;
        }
        long current = contacts.countByWorkspace(workspaceId);
        if (current + adding > plan.contactLimit()) {
            throw new PlanLimitExceededException(String.format(
                    "연락처 한도(%,d명)를 넘어요. 현재 %,d명에 %,d명을 추가하려면 플랜을 올려주세요.",
                    plan.contactLimit(), current, adding));
        }
    }

    /** 남은 연락처 수용량 — CSV 임포트가 루프 중 예산으로 쓴다. null = 무제한. */
    public Long remainingContactCapacity(Long workspaceId) {
        Plan plan = planOf(workspaceId);
        if (plan.contactLimit() == null) {
            return null;
        }
        return Math.max(0, plan.contactLimit() - contacts.countByWorkspace(workspaceId));
    }

    /** 멤버 1명 추가 가능 여부. */
    public void assertMemberAddable(Long workspaceId) {
        Plan plan = planOf(workspaceId);
        if (plan.memberLimit() == null) {
            return;
        }
        if (users.countByWorkspaceId(workspaceId) >= plan.memberLimit()) {
            throw new PlanLimitExceededException(String.format(
                    "멤버 한도(%d명)에 도달했습니다. 플랜을 올리면 팀원을 더 초대할 수 있어요.",
                    plan.memberLimit()));
        }
    }

    /** 발송 속도 설정값이 플랜 상한 이내인지 (null 설정=무제한 요청은 상한이 있으면 거부). */
    public void assertSendRateWithinCap(Long workspaceId, Integer requested) {
        Plan plan = planOf(workspaceId);
        Integer cap = plan.sendRateCap();
        if (cap == null) {
            return;
        }
        if (requested == null || requested > cap) {
            throw new IllegalArgumentException(String.format(
                    "발송 속도는 현재 플랜 상한인 초당 %d건 이내로 설정해야 합니다.", cap));
        }
    }

    /**
     * 요청이 쓰려는 기능이 플랜에 포함되는지 — 캠페인 등록 시 요청 필드로 판정한다.
     * 스타터: A/B·세그먼트 불가 / 스탠다드: 제목 A/B와 세그먼트까지(본문 A/B·승자
     * 자동발송은 프로) / 프로·엔터프라이즈: 전체. 메시지는 업셀 안내를 겸한다.
     */
    public void assertCampaignFeaturesAllowed(Long workspaceId,
                                              io.github.ahrimjang.mail.common.CreateCampaignRequest r) {
        boolean usesAb = r.abSubjectB() != null || r.abBodyB() != null || r.abTemplateId() != null
                || r.abEmailId() != null;
        boolean usesContentAb = r.abBodyB() != null || r.abTemplateId() != null || r.abEmailId() != null;
        boolean usesWinnerFlow = r.abTestPercent() != null;
        boolean usesSegment = r.segMinOpenPercent() != null || r.segMinClickPercent() != null;
        if (!usesAb && !usesSegment && !usesWinnerFlow) {
            return;   // 기본 기능만 — 플랜 조회조차 불필요
        }
        Plan plan = planOf(workspaceId);
        if (plan == Plan.STARTER) {
            if (usesAb || usesWinnerFlow) {
                throw new PlanLimitExceededException(
                        "A/B 테스트는 스탠다드 플랜부터 사용할 수 있어요. 플랜을 올리면 바로 쓸 수 있습니다.");
            }
            if (usesSegment) {
                throw new PlanLimitExceededException(
                        "참여도 세그먼트는 스탠다드 플랜부터 사용할 수 있어요. 플랜을 올리면 바로 쓸 수 있습니다.");
            }
        }
        if (plan == Plan.STANDARD && (usesContentAb || usesWinnerFlow)) {
            throw new PlanLimitExceededException(
                    "본문 A/B 테스트와 승자 자동발송은 프로 플랜부터예요. 스탠다드에서는 제목 A/B 까지 쓸 수 있습니다.");
        }
    }

    public Plan planOf(Long workspaceId) {
        return workspaces.findById(workspaceId)
                .map(Workspace::getPlan)
                .orElseThrow(() -> new NoSuchElementException("workspace not found: " + workspaceId));
    }
}
