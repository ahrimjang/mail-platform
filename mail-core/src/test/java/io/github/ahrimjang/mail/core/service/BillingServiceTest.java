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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    private static final long WS = 7L;

    @Mock
    private WorkspaceRepository workspaces;
    @Mock
    private PaymentRepository payments;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private ContactRepository contacts;
    @Mock
    private UserRepository users;
    @Mock
    private WorkspaceContext ctx;

    @InjectMocks
    private BillingService service;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
        lenient().when(ctx.isAdmin()).thenReturn(true);
        workspace = Workspace.of("결제사");
        workspace.setId(WS);
        lenient().when(workspaces.findById(WS)).thenReturn(Optional.of(workspace));
        lenient().when(payments.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registerCard_exchangesTheAuthKeyAndStoresTheBillingKey() {
        when(gateway.issueBillingKey("ws-7", "auth-123")).thenReturn("bk-xyz");

        service.registerCard("auth-123");

        assertThat(workspace.getBillingKey()).isEqualTo("bk-xyz");
        verify(workspaces).save(workspace);
    }

    @Test
    void upgrade_withoutACard_isRefusedBeforeAnyGatewayCall() {
        assertThatThrownBy(() -> service.changePlan("STANDARD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("카드");
        verify(gateway, never()).chargeBilling(any(), any(), anyInt(), any(), any());
    }

    @Test
    void upgrade_chargesFirst_thenAppliesThePlan_andRecordsTheReceipt() {
        workspace.setBillingKey("bk-xyz");
        when(gateway.chargeBilling(eq("bk-xyz"), eq("ws-7"), eq(9_900), anyString(), anyString()))
                .thenReturn("pay-1");

        PaymentView receipt = service.changePlan("STANDARD");

        assertThat(workspace.getPlan()).isEqualTo(Plan.STANDARD);
        assertThat(receipt.amountKrw()).isEqualTo(9_900);
        assertThat(receipt.status()).isEqualTo("APPROVED");
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(saved.capture());
        assertThat(saved.getValue().getPaymentKey()).isEqualTo("pay-1");
    }

    @Test
    void upgrade_whenTheChargeIsDeclined_recordsTheFailure_andKeepsTheOldPlan() {
        workspace.setBillingKey("bk-xyz");
        when(gateway.chargeBilling(any(), any(), anyInt(), any(), any()))
                .thenThrow(new PaymentGateway.PaymentGatewayException("한도 초과"));

        assertThatThrownBy(() -> service.changePlan("PRO"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("한도 초과");

        assertThat(workspace.getPlan()).isEqualTo(Plan.STARTER);   // 플랜 미적용
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(saved.capture());                    // 실패도 원장에 남는다
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        verify(workspaces, never()).save(any());
    }

    @Test
    void downgrade_appliesWithoutCharging_andClampsTheSendRateToTheNewCap() {
        workspace.setPlan(Plan.PRO);
        workspace.setSendRatePerSec(50);
        when(contacts.countByWorkspace(WS)).thenReturn(100L);
        when(users.countByWorkspaceId(WS)).thenReturn(1L);

        PaymentView receipt = service.changePlan("STARTER");

        assertThat(receipt).isNull();                              // 무결제
        assertThat(workspace.getPlan()).isEqualTo(Plan.STARTER);
        assertThat(workspace.getSendRatePerSec()).isEqualTo(5);    // 상한으로 재조정
        verify(gateway, never()).chargeBilling(any(), any(), anyInt(), any(), any());
    }

    @Test
    void downgrade_isBlockedWhenUsageExceedsTheTargetLimits() {
        workspace.setPlan(Plan.PRO);
        when(contacts.countByWorkspace(WS)).thenReturn(2_000L);    // 스타터 한도 500 초과

        assertThatThrownBy(() -> service.changePlan("STARTER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("연락처");
        assertThat(workspace.getPlan()).isEqualTo(Plan.PRO);
    }

    @Test
    void enterprise_goesThroughSalesNotSelfServe() {
        assertThatThrownBy(() -> service.changePlan("ENTERPRISE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("문의");
    }

    @Test
    void changingToTheCurrentPlan_isRejected() {
        assertThatThrownBy(() -> service.changePlan("STARTER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미");
    }
}
