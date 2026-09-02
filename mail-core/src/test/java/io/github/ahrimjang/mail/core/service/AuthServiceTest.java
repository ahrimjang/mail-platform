package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.AuthResponse;
import io.github.ahrimjang.mail.common.LoginRequest;
import io.github.ahrimjang.mail.common.SignupRequest;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.PasswordHasher;
import io.github.ahrimjang.mail.core.port.TokenService;
import io.github.ahrimjang.mail.core.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    /** The acting tenant every scoped call resolves to in these tests. */
    private static final long WS = 7L;

    @Mock
    private WorkspaceContext ctx;

    @BeforeEach
    void stubWorkspaceContext() {
        org.mockito.Mockito.lenient().when(ctx.currentWorkspaceId()).thenReturn(WS);
    }

    @Mock
    private UserRepository users;

    @Mock
    private PasswordHasher hasher;

    @Mock
    private TokenService tokens;

    @Mock
    private io.github.ahrimjang.mail.core.port.WorkspaceRepository workspaces;

    private LoginAttemptGuard attempts;

    @Mock
    private EmailVerificationService verification;   // mock 기본은 no-op = 인증 메일 무발송

    @Mock
    private io.github.ahrimjang.mail.core.port.GoogleIdentityVerifier google;

    private AuthService service;

    @BeforeEach
    void setUp() {
        attempts = new LoginAttemptGuard();   // 실물 사용 — 잠금 상호작용까지 함께 검증
        service = new AuthService(users, workspaces, hasher, tokens, attempts, verification, google, 0);
    }

    @BeforeEach
    void stubWorkspaceSave() {
        org.mockito.Mockito.lenient().when(workspaces.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    io.github.ahrimjang.mail.core.domain.Workspace w = inv.getArgument(0);
                    w.setId(WS);
                    return w;
                });
        org.mockito.Mockito.lenient().when(workspaces.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.empty());
    }

    @Test
    void signup_hashesPasswordAndSavesUser() {
        when(users.existsByEmail("new@x.com")).thenReturn(false);
        when(hasher.hash("raw-pw")).thenReturn("hashed-pw");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokens.issue(any(User.class))).thenReturn("jwt-token");

        AuthResponse response = service.signup(new SignupRequest("new@x.com", "raw-pw", "New User", null));

        verify(hasher).hash("raw-pw");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("new@x.com");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-pw");
        assertThat(response).isEqualTo(new AuthResponse("jwt-token", "new@x.com", "New User", "new 워크스페이스", "ADMIN", false));
    }

    @Test
    void signup_rejectsDuplicateEmail() {
        when(users.existsByEmail("dup@x.com")).thenReturn(true);

        assertThatThrownBy(() -> service.signup(new SignupRequest("dup@x.com", "pw", "Dup", null)))
                .isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
    }

    @Test
    void signup_rejectsBlankEmailOrPassword() {
        assertThatThrownBy(() -> service.signup(new SignupRequest(" ", "pw", "X", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.signup(new SignupRequest("a@x.com", null, "X", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
    }

    private static final String IP = "203.0.113.7";

    @Test
    void login_withCorrectPasswordReturnsIssuedToken() {
        User user = User.register("me@x.com", "stored-hash", "Me");
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(user));
        when(hasher.matches("raw-pw", "stored-hash")).thenReturn(true);
        when(tokens.issue(user)).thenReturn("jwt-token");

        AuthResponse response = service.login(new LoginRequest("me@x.com", "raw-pw"), IP);

        assertThat(response).isEqualTo(new AuthResponse("jwt-token", "me@x.com", "Me", null, null, false));
    }

    @Test
    void login_rejectsWrongPassword() {
        User user = User.register("me@x.com", "stored-hash", "Me");
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(user));
        when(hasher.matches("wrong-pw", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("me@x.com", "wrong-pw"), IP))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tokens, never()).issue(any());
    }

    @Test
    void login_rejectsUnknownEmail() {
        when(users.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost@x.com", "pw"), IP))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tokens, never()).issue(any());
    }

    @Test
    void login_locksTheAccountAfterRepeatedFailures_withoutConsultingThePasswordHasher() {
        User user = User.register("me@x.com", "stored-hash", "Me");
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(user));
        when(hasher.matches(any(), any())).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("me@x.com", "wrong"), IP))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // 6번째부터는 잠금 — BCrypt 비용(hasher)에 도달하기 전에 429 계열 예외로 끊긴다
        org.mockito.Mockito.clearInvocations(hasher);
        assertThatThrownBy(() -> service.login(new LoginRequest("me@x.com", "wrong"), IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        verify(hasher, never()).matches(any(), any());
    }

    @Test
    void login_successResetsTheFailureCount() {
        User user = User.register("me@x.com", "stored-hash", "Me");
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(user));
        when(hasher.matches("wrong", "stored-hash")).thenReturn(false);
        when(hasher.matches("right", "stored-hash")).thenReturn(true);
        when(tokens.issue(user)).thenReturn("jwt");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("me@x.com", "wrong"), IP))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        service.login(new LoginRequest("me@x.com", "right"), IP);   // 성공이 카운트를 지움

        // 다시 4번 틀려도 잠기지 않는다 (연속 실패가 리셋됐으므로)
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("me@x.com", "wrong"), IP))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void signup_blockedWhenBetaCapReached() {
        AuthService capped = new AuthService(users, workspaces, hasher, tokens, attempts, verification, google, 10);
        when(users.existsByEmail("late@x.com")).thenReturn(false);
        when(workspaces.count()).thenReturn(10L);   // 정원 도달

        assertThatThrownBy(() -> capped.signup(new io.github.ahrimjang.mail.common.SignupRequest(
                "late@x.com", "password1", "지각생", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정원");
        verify(workspaces, never()).save(any());
        verify(users, never()).save(any());
    }

    @Test
    void signup_allowedBelowBetaCap() {
        AuthService capped = new AuthService(users, workspaces, hasher, tokens, attempts, verification, google, 10);
        when(users.existsByEmail("ok@x.com")).thenReturn(false);
        when(workspaces.count()).thenReturn(9L);   // 아직 자리 있음
        when(hasher.hash("password1")).thenReturn("h");
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokens.issue(any())).thenReturn("jwt");

        capped.signup(new io.github.ahrimjang.mail.common.SignupRequest("ok@x.com", "password1", "막차", null));

        verify(workspaces).save(any());
    }

    @Test
    void signup_rejectsDisposableEmailDomains() {
        assertThatThrownBy(() -> service.signup(new io.github.ahrimjang.mail.common.SignupRequest(
                "farmer@mailinator.com", "password1", "농부", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일회용");
        verify(users, never()).save(any());
    }

    // ── 구글 로그인 ──────────────────────────────────────────────────

    private static io.github.ahrimjang.mail.core.port.GoogleIdentityVerifier.GoogleIdentity gid(
            String email, boolean verified) {
        return new io.github.ahrimjang.mail.core.port.GoogleIdentityVerifier.GoogleIdentity(
                "g-sub-123", email, "구글유저", verified);
    }

    @Test
    void google_newUser_signsUpVerifiedWithoutVerificationMail() {
        when(google.verify("id-token")).thenReturn(gid("new@gmail.com", true));
        when(users.findByEmail("new@gmail.com")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokens.issue(any(User.class))).thenReturn("jwt");

        var response = service.loginWithGoogle("id-token");

        assertThat(response.emailVerified()).isTrue();
        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getAuthProvider()).isEqualTo("GOOGLE");
        assertThat(saved.getValue().getProviderSubject()).isEqualTo("g-sub-123");
        assertThat(saved.getValue().getPasswordHash()).isNull();
        assertThat(saved.getValue().getRole()).isEqualTo("ADMIN");
        // 구글이 메일함 소유를 검증했으니 우리 인증 메일은 안 나간다
        verify(verification, never()).send(any());
    }

    @Test
    void google_existingLocalUser_linksAndBackfillsVerification() {
        User existing = User.register("me@x.com", "stored-hash", "Me");
        existing.setWorkspaceId(1L);
        when(google.verify("id-token")).thenReturn(gid("me@x.com", true));
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(existing));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokens.issue(existing)).thenReturn("jwt");

        var response = service.loginWithGoogle("id-token");

        assertThat(response.emailVerified()).isTrue();
        assertThat(existing.getProviderSubject()).isEqualTo("g-sub-123");
        assertThat(existing.getPasswordHash()).isEqualTo("stored-hash");   // 기존 비밀번호 로그인 유지
        assertThat(existing.isEmailVerified()).isTrue();                   // 구글 검증이 우리 인증을 갈음
    }

    @Test
    void google_invalidToken_rejected() {
        when(google.verify("bad")).thenThrow(new IllegalArgumentException("유효하지 않은 Google 토큰입니다."));

        assertThatThrownBy(() -> service.loginWithGoogle("bad"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
    }

    @Test
    void google_unverifiedEmail_isRejectedOnBothPaths() {
        // 구글이 소유를 검증하지 않은 주소를 받아주면, 그 주소를 주장하는 것만으로
        // 남의 워크스페이스에 비밀번호 없이 들어갈 수 있다(계정 탈취).
        when(google.verify("id-token")).thenReturn(gid("victim@company.com", false));

        assertThatThrownBy(() -> service.loginWithGoogle("id-token"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).findByEmail(any());   // 조회조차 하지 않는다
        verify(users, never()).save(any());
        verify(tokens, never()).issue(any());
    }

    @Test
    void google_blankEmail_isRejected() {
        // 빈 이메일을 허용하면 서로 다른 사용자가 같은 "" 계정으로 병합된다.
        when(google.verify("id-token")).thenReturn(gid("", true));

        assertThatThrownBy(() -> service.loginWithGoogle("id-token"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
    }

    @Test
    void google_disposableDomain_cannotSignUp() {
        // 일반 가입에만 있던 입구 방어 — 구글 경로로도 우회할 수 없어야 한다.
        when(google.verify("id-token")).thenReturn(gid("throwaway@mailinator.com", true));
        when(users.findByEmail("throwaway@mailinator.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loginWithGoogle("id-token"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
        verify(workspaces, never()).save(any());
    }

    @Test
    void login_passwordlessGoogleAccount_failsLikeAnyOtherBadLogin() {
        // 소셜 계정임을 알려주면 친절하지만, 임의 주소로 "가입 여부 + 소셜 여부"를
        // 무제한 조회할 수 있는 계정 열거 오라클이 된다. 문구는 일반 실패와 동일하게,
        // 실패 카운트도 함께 올린다.
        User social = User.registerSocial("g@gmail.com", "구글유저", "GOOGLE", "g-sub-123");
        when(users.findByEmail("g@gmail.com")).thenReturn(Optional.of(social));

        assertThatThrownBy(() -> service.login(new LoginRequest("g@gmail.com", "whatever"), IP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Google");
        verify(hasher, never()).matches(any(), any());   // null 해시로 BCrypt 에 안 들어간다
    }
}
