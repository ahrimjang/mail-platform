package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.AuthResponse;
import io.github.ahrimjang.mail.common.LoginRequest;
import io.github.ahrimjang.mail.common.SignupRequest;
import io.github.ahrimjang.mail.core.domain.User;
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

    @Mock
    private UserRepository users;

    @Mock
    private PasswordHasher hasher;

    @Mock
    private TokenService tokens;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, hasher, tokens);
    }

    @Test
    void signup_hashesPasswordAndSavesUser() {
        when(users.existsByEmail("new@x.com")).thenReturn(false);
        when(hasher.hash("raw-pw")).thenReturn("hashed-pw");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokens.issue(any(User.class))).thenReturn("jwt-token");

        AuthResponse response = service.signup(new SignupRequest("new@x.com", "raw-pw", "New User"));

        verify(hasher).hash("raw-pw");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("new@x.com");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-pw");
        assertThat(response).isEqualTo(new AuthResponse("jwt-token", "new@x.com", "New User"));
    }

    @Test
    void signup_rejectsDuplicateEmail() {
        when(users.existsByEmail("dup@x.com")).thenReturn(true);

        assertThatThrownBy(() -> service.signup(new SignupRequest("dup@x.com", "pw", "Dup")))
                .isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
    }

    @Test
    void signup_rejectsBlankEmailOrPassword() {
        assertThatThrownBy(() -> service.signup(new SignupRequest(" ", "pw", "X")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.signup(new SignupRequest("a@x.com", null, "X")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
    }

    @Test
    void login_withCorrectPasswordReturnsIssuedToken() {
        User user = User.register("me@x.com", "stored-hash", "Me");
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(user));
        when(hasher.matches("raw-pw", "stored-hash")).thenReturn(true);
        when(tokens.issue(user)).thenReturn("jwt-token");

        AuthResponse response = service.login(new LoginRequest("me@x.com", "raw-pw"));

        assertThat(response).isEqualTo(new AuthResponse("jwt-token", "me@x.com", "Me"));
    }

    @Test
    void login_rejectsWrongPassword() {
        User user = User.register("me@x.com", "stored-hash", "Me");
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(user));
        when(hasher.matches("wrong-pw", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("me@x.com", "wrong-pw")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tokens, never()).issue(any());
    }

    @Test
    void login_rejectsUnknownEmail() {
        when(users.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost@x.com", "pw")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tokens, never()).issue(any());
    }
}
