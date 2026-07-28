package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.PasswordResetToken;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.PasswordHasher;
import io.github.ahrimjang.mail.core.port.PasswordResetTokenRepository;
import io.github.ahrimjang.mail.core.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {

    @Mock UserRepository users;
    @Mock PasswordResetTokenRepository tokens;
    @Mock PasswordHasher hasher;
    @Mock MailSender sender;
    @Mock LoginAttemptGuard attempts;

    PasswordResetService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        service = new PasswordResetService(users, tokens, hasher, sender, attempts,
                "http://console.test");
    }

    private User userWithId(long id, String email) {
        User u = User.register(email, "old-hash", "테스터");
        u.setId(id);
        return u;
    }

    // ── request ──────────────────────────────────────────────────────

    @Test
    void request_unknownEmail_staysSilentAndSendsNothing() {
        when(users.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

        service.request("ghost@x.com");   // 예외 없이 조용히 끝나야 함 (열거 방지)

        verify(tokens, never()).save(any());
        verify(sender, never()).send(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void request_malformedEmail_ignoredWithoutLookup() {
        service.request("not-an-email");
        service.request(null);

        verify(users, never()).findByEmail(any());
    }

    @Test
    void request_withinCooldown_doesNotIssueOrSend() {
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(userWithId(7L, "me@x.com")));
        // 직전 발급이 방금 전 — 2분 쿨다운에 걸림
        when(tokens.latestIssuedAt(7L)).thenReturn(Optional.of(Instant.now().minusSeconds(30)));

        service.request("me@x.com");

        verify(tokens, never()).save(any());
        verify(sender, never()).send(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void request_issuesTokenAndMailsResetLink() {
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(userWithId(7L, "me@x.com")));
        when(tokens.latestIssuedAt(7L)).thenReturn(Optional.empty());
        when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.request("me@x.com");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().isUsable(Instant.now())).isTrue();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("me@x.com"), eq("[Outpace] 비밀번호 재설정 안내"),
                body.capture(), isNull(), eq("Outpace"), isNull());
        assertThat(body.getValue())
                .contains("http://console.test/reset-password?token=" + saved.getValue().getToken());
    }

    @Test
    void request_mailFailure_isSwallowed() {
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(userWithId(7L, "me@x.com")));
        when(tokens.latestIssuedAt(7L)).thenReturn(Optional.empty());
        when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new MailSender.MailSendException("smtp down", null))
                .when(sender).send(anyString(), anyString(), anyString(), any(), any(), any());

        service.request("me@x.com");   // 응답 동일성 유지 — 예외가 새어나오면 안 됨
    }

    // ── confirm ──────────────────────────────────────────────────────

    @Test
    void confirm_shortPassword_rejected() {
        assertThatThrownBy(() -> service.confirm("tok", "1234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8자");
        verify(tokens, never()).findByToken(any());
    }

    @Test
    void confirm_unknownToken_rejected() {
        when(tokens.findByToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("nope", "newpassword1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료됐거나 이미 사용");
    }

    @Test
    void confirm_expiredToken_rejected() {
        PasswordResetToken t = PasswordResetToken.issue(7L);
        t.setExpiresAt(Instant.now().minusSeconds(1));
        when(tokens.findByToken(t.getToken())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.confirm(t.getToken(), "newpassword1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirm_usedToken_rejected() {
        PasswordResetToken t = PasswordResetToken.issue(7L);
        t.markUsed(Instant.now());
        when(tokens.findByToken(t.getToken())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.confirm(t.getToken(), "newpassword1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirm_replacesHash_consumesToken_andUnlocksLogin() {
        PasswordResetToken t = PasswordResetToken.issue(7L);
        User user = userWithId(7L, "me@x.com");
        when(tokens.findByToken(t.getToken())).thenReturn(Optional.of(t));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(hasher.hash("newpassword1")).thenReturn("new-hash");

        service.confirm(t.getToken(), "newpassword1");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(users).save(user);
        assertThat(t.getUsedAt()).isNotNull();      // 1회용 소진
        verify(tokens).save(t);
        verify(attempts).onSuccess("me@x.com");     // 잠금 해제
    }
}
