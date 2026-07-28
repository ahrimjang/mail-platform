package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Plan;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 플랜 한도 임계값 — 스타터 기준으로 경계(직전 허용/도달 차단)를 검증한다. */
@ExtendWith(MockitoExtension.class)
class PlanLimitsTest {

    private static final long WS = 7L;

    @Mock
    private WorkspaceRepository workspaces;
    @Mock
    private MailMessageRepository messages;
    @Mock
    private ContactRepository contacts;
    @Mock
    private UserRepository users;

    @InjectMocks
    private PlanLimits limits;

    private final Workspace starter = Workspace.of("스타터사");

    @BeforeEach
    void stubWorkspace() {
        starter.setId(WS);
        lenient().when(workspaces.findById(WS)).thenReturn(Optional.of(starter));
    }

    @Test
    void campaignRegistration_allowsBelowAndBlocksAtTheMonthlyLimit() {
        when(messages.countSentByWorkspaceSince(eq(WS), any())).thenReturn(999L);
        assertThatCode(() -> limits.assertCampaignRegistrationAllowed(WS)).doesNotThrowAnyException();

        when(messages.countSentByWorkspaceSince(eq(WS), any())).thenReturn(1_000L);
        assertThatThrownBy(() -> limits.assertCampaignRegistrationAllowed(WS))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("1,000");
    }

    @Test
    void campaignRegistration_isUnlimitedOnEnterprise() {
        starter.setPlan(Plan.ENTERPRISE);
        assertThatCode(() -> limits.assertCampaignRegistrationAllowed(WS)).doesNotThrowAnyException();
        // 무제한 플랜은 사용량 조회 자체가 없다
        org.mockito.Mockito.verify(messages, org.mockito.Mockito.never())
                .countSentByWorkspaceSince(any(), any());
    }

    @Test
    void contacts_capacityIsTheGapBetweenLimitAndCurrent() {
        when(contacts.countByWorkspace(WS)).thenReturn(498L);

        assertThatCode(() -> limits.assertContactsAddable(WS, 2)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.assertContactsAddable(WS, 3))
                .isInstanceOf(PlanLimitExceededException.class);
        assertThat(limits.remainingContactCapacity(WS)).isEqualTo(2L);
    }

    @Test
    void members_starterAllowsExactlyOne() {
        when(users.countByWorkspaceId(WS)).thenReturn(1L);   // 가입 시 만든 ADMIN 1명

        assertThatThrownBy(() -> limits.assertMemberAddable(WS))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("멤버 한도");
    }

    private static io.github.ahrimjang.mail.common.CreateCampaignRequest campaignWith(
            String abSubjectB, String abBodyB, Integer abTestPercent, Integer segMinOpen) {
        return new io.github.ahrimjang.mail.common.CreateCampaignRequest(
                "s", "<p>b</p>", java.util.List.of("a@x.com"), null, null, null, null, null,
                abSubjectB, abBodyB, null, null, abTestPercent, null, null,
                null, null, segMinOpen, null, null);
    }

    @Test
    void features_starterCannotUseAbOrSegments() {
        assertThatThrownBy(() -> limits.assertCampaignFeaturesAllowed(WS, campaignWith("B제목", null, null, null)))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("스탠다드 플랜부터");
        assertThatThrownBy(() -> limits.assertCampaignFeaturesAllowed(WS, campaignWith(null, null, null, 30)))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("세그먼트");
        // 기본 기능만 쓰는 요청은 플랜 조회조차 하지 않는다
        assertThatCode(() -> limits.assertCampaignFeaturesAllowed(WS, campaignWith(null, null, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void features_standardGetsSubjectAbButNotContentAbOrWinnerFlow() {
        starter.setPlan(Plan.STANDARD);

        assertThatCode(() -> limits.assertCampaignFeaturesAllowed(WS, campaignWith("B제목", null, null, 30)))
                .doesNotThrowAnyException();                       // 제목 A/B + 세그먼트 OK
        assertThatThrownBy(() -> limits.assertCampaignFeaturesAllowed(WS, campaignWith("B제목", "<p>B본문</p>", null, null)))
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("프로 플랜부터");             // 본문 B는 프로
        assertThatThrownBy(() -> limits.assertCampaignFeaturesAllowed(WS, campaignWith("B제목", null, 20, null)))
                .isInstanceOf(PlanLimitExceededException.class);   // 승자 플로우도 프로
    }

    @Test
    void features_proGetsEverything() {
        starter.setPlan(Plan.PRO);
        assertThatCode(() -> limits.assertCampaignFeaturesAllowed(WS, campaignWith("B", "<p>B</p>", 20, 30)))
                .doesNotThrowAnyException();
    }

    @Test
    void sendRate_mustBeWithinTheCap_andUnsetIsRejectedWhenCapped() {
        assertThatCode(() -> limits.assertSendRateWithinCap(WS, 5)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.assertSendRateWithinCap(WS, 6))
                .isInstanceOf(IllegalArgumentException.class);
        // 상한이 있는 플랜에서 "무제한(미설정)" 요청은 상한 우회라 거부
        assertThatThrownBy(() -> limits.assertSendRateWithinCap(WS, null))
                .isInstanceOf(IllegalArgumentException.class);

        starter.setPlan(Plan.ENTERPRISE);
        assertThatCode(() -> limits.assertSendRateWithinCap(WS, null)).doesNotThrowAnyException();
    }
}
