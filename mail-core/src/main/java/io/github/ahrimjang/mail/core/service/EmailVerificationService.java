package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.EmailVerificationToken;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.EmailVerificationTokenRepository;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.UserRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 가입 이메일 소유 검증 — 인증 메일도 재설정 메일과 같이 자체 발송 인프라
 * ({@link MailSender})로 나간다.
 *
 * <p>가입 직후 자동 발송되고, 인증을 마칠 때까지 발송 경로(캠페인 등록·트랜잭셔널)가
 * {@link #assertCurrentUserVerified()}로 잠긴다 — 소유 확인 안 된 주소를 발신 주체로
 * 두는 것은 스팸 오남용 통로가 되기 때문. 콘솔 열람·연락처 정리 등 나머지 기능은
 * 인증 전에도 쓸 수 있다.
 *
 * <p>토큰은 1회용·24시간, 재발송은 2분 쿨다운(조용히 무시). 메일 발송 실패가
 * 가입 자체를 실패시키지는 않는다 — 배너의 재발송 버튼이 복구 경로다.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(2);

    private final UserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final MailSender sender;
    private final WorkspaceContext ctx;
    private final String consoleUrl;

    public EmailVerificationService(UserRepository users, EmailVerificationTokenRepository tokens,
                                    MailSender sender, WorkspaceContext ctx,
                                    @Value("${app.console-url:http://localhost:5175}") String consoleUrl) {
        this.users = users;
        this.tokens = tokens;
        this.sender = sender;
        this.ctx = ctx;
        this.consoleUrl = consoleUrl;
    }

    /** 인증 메일 발송 — 가입 직후와 재발송이 공유하는 경로. 쿨다운 중이면 조용히 무시. */
    public void send(User user) {
        if (user.isEmailVerified()) {
            return;
        }
        Instant now = Instant.now();
        boolean cooling = tokens.latestIssuedAt(user.getId())
                .map(t -> now.isBefore(t.plus(RESEND_COOLDOWN)))
                .orElse(false);
        if (cooling) {
            log.info("인증 메일: 쿨다운 중 (무시) user={}", user.getId());
            return;
        }
        EmailVerificationToken token = tokens.save(EmailVerificationToken.issue(user.getId()));
        String link = consoleUrl + "/verify-email?token=" + token.getToken();
        try {
            sender.send(user.getEmail(), "[Outpace] 이메일 주소를 인증해주세요", verifyMail(link),
                    null, "Outpace", null);
            log.info("인증 메일 발송: user={}", user.getId());
        } catch (Exception e) {
            // 가입 흐름을 막지 않는다 — 콘솔 배너의 재발송 버튼이 복구 경로
            log.error("인증 메일 발송 실패: user={}", user.getId(), e);
        }
    }

    /** 콘솔 배너의 재발송 버튼 — 현재 로그인한 사용자에게 다시 보낸다. */
    public void resendForCurrentUser() {
        users.findByEmail(ctx.currentUserEmail()).ifPresent(this::send);
    }

    /** 현재 로그인한 사용자의 인증 여부 — 콘솔 배너 표시 조건. */
    public boolean currentUserVerified() {
        return users.findByEmail(ctx.currentUserEmail())
                .map(User::isEmailVerified)
                .orElse(false);
    }

    /** 발송 경로 게이트 — 미인증이면 409로 이어지는 IllegalStateException. */
    public void assertCurrentUserVerified() {
        if (!currentUserVerified()) {
            throw new IllegalStateException(
                    "가입 이메일 인증 후 발송할 수 있어요. 받은편지함의 인증 메일을 확인해주세요.");
        }
    }

    /** 토큰 검증 + 인증 처리. 성공 시 토큰 소진. */
    public void confirm(String tokenValue) {
        Instant now = Instant.now();
        EmailVerificationToken token = tokens.findByToken(tokenValue)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new IllegalArgumentException(
                        "인증 링크가 만료됐거나 이미 사용됐어요. 콘솔에서 인증 메일을 다시 요청해주세요."));
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("계정을 찾을 수 없습니다."));

        if (!user.isEmailVerified()) {
            user.setEmailVerifiedAt(now);
            users.save(user);
        }
        token.markUsed(now);
        tokens.save(token);
        log.info("이메일 인증 완료: user={}", user.getId());
    }

    private static String verifyMail(String link) {
        return """
                <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
                  <h2 style="margin:0 0 12px">이메일 주소 인증</h2>
                  <p style="color:#555;line-height:1.7">Outpace 가입을 환영해요! 아래 버튼을 눌러
                     이 주소가 본인 것임을 확인해주세요. 인증을 마쳐야 캠페인을 보낼 수 있어요.
                     이 링크는 <b>24시간 동안, 한 번만</b> 쓸 수 있어요.</p>
                  <p style="margin:24px 0">
                    <a href="%s" style="background:#4f46e5;color:#fff;padding:12px 20px;
                       border-radius:8px;text-decoration:none;font-weight:700">이메일 인증하기</a>
                  </p>
                  <p style="color:#999;font-size:12px;line-height:1.6">본인이 가입하지 않았다면 이
                     메일은 무시하세요.<br>버튼이 안 눌리면 링크를 복사하세요: %s</p>
                </div>
                """.formatted(link, link);
    }
}
