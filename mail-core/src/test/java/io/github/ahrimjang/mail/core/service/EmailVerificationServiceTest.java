package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.EmailVerificationToken;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.EmailVerificationTokenRepository;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {

    @Mock UserRepository users;
    @Mock EmailVerificationTokenRepository tokens;
    @Mock MailSender sender;
    @Mock WorkspaceContext ctx;

    EmailVerificationService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        service = new EmailVerificationService(users, tokens, sender, ctx, "http://console.test");
    }

    private User userWithId(long id, String email, boolean verified) {
        User u = User.register(email, "hash", "테스터");
        u.setId(id);
        if (verified) u.setEmailVerifiedAt(Instant.now());
        return u;
    }

    // ── send ─────────────────────────────────────────────────────────

    @Test
    void send_issuesTokenAndMailsVerificationLink() {
        User user = userWithId(7L, "me@x.com", false);
        when(tokens.latestIssuedAt(7L)).thenReturn(Optional.empty());
        when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.send(user);

        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("me@x.com"), eq("[Outpace] 이메일 주소를 인증해주세요"),
                body.capture(), isNull(), eq("Outpace"), isNull());
        assertThat(body.getValue())
                .contains("http://console.test/verify-email?token=" + saved.getValue().getToken());
    }

    @Test
    void send_alreadyVerified_doesNothing() {
        service.send(userWithId(7L, "me@x.com", true));

        verify(tokens, never()).save(any());
        verify(sender, never()).send(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void send_withinCooldown_doesNotIssueOrSend() {
        User user = userWithId(7L, "me@x.com", false);
        when(tokens.latestIssuedAt(7L)).thenReturn(Optional.of(Instant.now().minusSeconds(30)));

        service.send(user);

        verify(tokens, never()).save(any());
        verify(sender, never()).send(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void send_mailFailure_isSwallowed() {
        User user = userWithId(7L, "me@x.com", false);
        when(tokens.latestIssuedAt(7L)).thenReturn(Optional.empty());
        when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new MailSender.MailSendException("smtp down"))
                .when(sender).send(anyString(), anyString(), anyString(), any(), any(), any());

        assertThatCode(() -> service.send(user)).doesNotThrowAnyException();   // 가입 흐름을 막지 않는다
    }

    // ── 게이트 ───────────────────────────────────────────────────────

    @Test
    void assertCurrentUserVerified_blocksUnverifiedUser() {
        when(ctx.currentUserEmail()).thenReturn("me@x.com");
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(userWithId(7L, "me@x.com", false)));

        assertThatThrownBy(() -> service.assertCurrentUserVerified())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("인증");
    }

    @Test
    void assertCurrentUserVerified_passesVerifiedUser() {
        when(ctx.currentUserEmail()).thenReturn("me@x.com");
        when(users.findByEmail("me@x.com")).thenReturn(Optional.of(userWithId(7L, "me@x.com", true)));

        assertThatCode(() -> service.assertCurrentUserVerified()).doesNotThrowAnyException();
    }

    // ── confirm ──────────────────────────────────────────────────────

    @Test
    void confirm_marksUserVerifiedAndConsumesToken() {
        EmailVerificationToken t = EmailVerificationToken.issue(7L);
        User user = userWithId(7L, "me@x.com", false);
        when(tokens.findByToken(t.getToken())).thenReturn(Optional.of(t));
        when(users.findById(7L)).thenReturn(Optional.of(user));

        service.confirm(t.getToken());

        assertThat(user.isEmailVerified()).isTrue();
        verify(users).save(user);
        assertThat(t.getUsedAt()).isNotNull();      // 1회용 소진
        verify(tokens).save(t);
    }

    @Test
    void confirm_unknownToken_rejected() {
        when(tokens.findByToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료됐거나 이미 사용");
    }

    @Test
    void confirm_expiredToken_rejected() {
        EmailVerificationToken t = EmailVerificationToken.issue(7L);
        t.setExpiresAt(Instant.now().minusSeconds(1));
        when(tokens.findByToken(t.getToken())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.confirm(t.getToken()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirm_usedToken_rejected() {
        EmailVerificationToken t = EmailVerificationToken.issue(7L);
        t.markUsed(Instant.now());
        when(tokens.findByToken(t.getToken())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.confirm(t.getToken()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirm_alreadyVerifiedUser_stillConsumesTokenWithoutRewritingUser() {
        // 두 번째 링크 클릭 등 — 인증 시각은 처음 것을 보존한다
        EmailVerificationToken t = EmailVerificationToken.issue(7L);
        User user = userWithId(7L, "me@x.com", true);
        Instant firstVerifiedAt = user.getEmailVerifiedAt();
        when(tokens.findByToken(t.getToken())).thenReturn(Optional.of(t));
        when(users.findById(7L)).thenReturn(Optional.of(user));

        service.confirm(t.getToken());

        assertThat(user.getEmailVerifiedAt()).isEqualTo(firstVerifiedAt);
        verify(users, never()).save(any());
        verify(tokens).save(t);
    }
}
