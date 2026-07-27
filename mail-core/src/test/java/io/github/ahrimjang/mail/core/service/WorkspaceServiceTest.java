package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.CreateWorkspaceUserRequest;
import io.github.ahrimjang.mail.common.UpdateUserRoleRequest;
import io.github.ahrimjang.mail.common.UpdateWorkspaceRequest;
import io.github.ahrimjang.mail.common.WorkspaceUserView;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.PasswordHasher;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    private static final long WS = 7L;

    @Mock
    private WorkspaceRepository workspaces;
    @Mock
    private UserRepository users;
    @Mock
    private PasswordHasher hasher;
    @Mock
    private WorkspaceContext ctx;
    @Mock
    private PlanLimits planLimits;   // mock 기본은 no-op = 한도 여유 상태

    @InjectMocks
    private WorkspaceService service;

    @BeforeEach
    void stubContext() {
        lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
        lenient().when(ctx.isAdmin()).thenReturn(true);
        lenient().when(planLimits.monthlySent(WS)).thenReturn(1234L);
    }

    private static User member(long id, String email, String role) {
        User u = User.register(email, "hash", null);
        u.setId(id);
        u.setWorkspaceId(WS);
        u.setRole(role);
        return u;
    }

    @Test
    void update_requiresTheAdminRole() {
        when(ctx.isAdmin()).thenReturn(false);

        assertThatThrownBy(() -> service.update(new UpdateWorkspaceRequest("회사", null)))
                .isInstanceOf(ForbiddenException.class);
        verify(workspaces, never()).save(any());
    }

    @Test
    void update_rejectsANonPositiveSendRate() {
        Workspace ws = Workspace.of("회사");
        ws.setId(WS);
        when(workspaces.findById(WS)).thenReturn(Optional.of(ws));

        assertThatThrownBy(() -> service.update(new UpdateWorkspaceRequest("회사", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send rate");
        verify(workspaces, never()).save(any());
    }

    @Test
    void update_persistsNameAndSendRate() {
        Workspace ws = Workspace.of("회사");
        ws.setId(WS);
        when(workspaces.findById(WS)).thenReturn(Optional.of(ws));
        when(workspaces.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.countByWorkspaceId(WS)).thenReturn(2L);

        var view = service.update(new UpdateWorkspaceRequest("에이컴퍼니", 14));

        assertThat(view.name()).isEqualTo("에이컴퍼니");
        assertThat(view.sendRatePerSec()).isEqualTo(14);
        // 과금 기준인 월 발송량과 플랜/한도는 모든 view에 동봉된다
        assertThat(view.monthlySent()).isEqualTo(1234L);
        assertThat(view.plan()).isEqualTo("STARTER");
        assertThat(view.monthlySendLimit()).isEqualTo(1_000L);
    }

    @Test
    void update_rejectsASendRateAboveThePlanCap() {
        Workspace ws = Workspace.of("회사");
        ws.setId(WS);
        when(workspaces.findById(WS)).thenReturn(Optional.of(ws));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("발송 속도는 현재 플랜 상한인 초당 5건 이내"))
                .when(planLimits).assertSendRateWithinCap(WS, 50);

        assertThatThrownBy(() -> service.update(new UpdateWorkspaceRequest("회사", 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("플랜 상한");
        verify(workspaces, never()).save(any());
    }

    @Test
    void addMember_isBlockedAtThePlanMemberLimit() {
        when(users.existsByEmail("op@a.com")).thenReturn(false);
        org.mockito.Mockito.doThrow(new PlanLimitExceededException("멤버 한도(1명)에 도달"))
                .when(planLimits).assertMemberAddable(WS);

        assertThatThrownBy(() -> service.addMember(
                new CreateWorkspaceUserRequest("op@a.com", "pw12345", "운영자", "OPERATOR")))
                .isInstanceOf(PlanLimitExceededException.class);
        verify(users, never()).save(any());
    }

    @Test
    void addMember_createsAnOperatorInsideTheActingWorkspace() {
        when(users.existsByEmail("op@a.com")).thenReturn(false);
        when(hasher.hash("pw12345")).thenReturn("hashed");
        when(users.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(11L);
            return u;
        });

        WorkspaceUserView view = service.addMember(
                new CreateWorkspaceUserRequest("op@a.com", "pw12345", "운영자", "OPERATOR"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getWorkspaceId()).isEqualTo(WS);
        assertThat(captor.getValue().getRole()).isEqualTo("OPERATOR");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(view.role()).isEqualTo("OPERATOR");
    }

    @Test
    void changeRole_refusesToDemoteTheLastAdmin() {
        User admin = member(1L, "boss@a.com", "ADMIN");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.findByWorkspaceId(WS)).thenReturn(List.of(admin, member(2L, "op@a.com", "OPERATOR")));

        assertThatThrownBy(() -> service.changeRole(1L, new UpdateUserRoleRequest("OPERATOR")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last admin");
        verify(users, never()).save(any());
    }

    @Test
    void changeRole_ofAnotherTenantsUserReadsAsAbsent() {
        User foreign = member(9L, "x@b.com", "OPERATOR");
        foreign.setWorkspaceId(99L);
        when(users.findById(9L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.changeRole(9L, new UpdateUserRoleRequest("ADMIN")))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
