package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.PasswordResetToken;
import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.MailSender;
import io.github.ahrimjang.mail.core.port.PasswordHasher;
import io.github.ahrimjang.mail.core.port.PasswordResetTokenRepository;
import io.github.ahrimjang.mail.core.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 비밀번호 재설정 — 우리가 이메일 발송 플랫폼이므로 재설정 메일도 자체 인프라
 * ({@link MailSender})로 나간다.
 *
 * <p>보안 성질 세 가지:
 * <ul>
 *   <li><b>계정 열거 방지</b> — 요청은 계정 존재 여부와 무관하게 같은 응답. 없는
 *       주소면 조용히 아무것도 안 한다.</li>
 *   <li><b>스팸 쿨다운</b> — 같은 계정으로 2분 안의 재요청은 조용히 무시(남의
 *       메일함 폭탄 방지). 역시 응답은 동일.</li>
 *   <li><b>토큰은 1회용·30분</b> — 사용 즉시 소진, 만료 후 무효.</li>
 * </ul>
 * 재설정 성공은 로그인 실패 잠금도 푼다 — "비밀번호를 잊어 여러 번 틀린" 정상
 * 사용자의 복구 경로가 잠금에 막히면 안 되므로.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(2);

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordHasher hasher;
    private final MailSender sender;
    private final LoginAttemptGuard attempts;
    private final String consoleUrl;

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                PasswordHasher hasher, MailSender sender, LoginAttemptGuard attempts,
                                @Value("${app.console-url:http://localhost:5175}") String consoleUrl) {
        this.users = users;
        this.tokens = tokens;
        this.hasher = hasher;
        this.sender = sender;
        this.attempts = attempts;
        this.consoleUrl = consoleUrl;
    }

    /** 재설정 요청 — 어떤 입력이든 조용히 202 (계정 열거 방지). */
    public void request(String email) {
        if (email == null || !email.contains("@")) {
            return;
        }
        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            log.info("재설정 요청: 미가입 주소 (무시)");
            return;
        }
        // 소셜 전용 계정(비밀번호 없음)에는 토큰을 발급하지 않는다 — 발급하면 재설정만으로
        // 비밀번호 로그인이 가능한 계정으로 바뀌어 인증 표면이 늘어난다. 응답은 동일(열거 방지).
        if (user.getPasswordHash() == null) {
            log.info("재설정 요청: 소셜 전용 계정 (무시) user={}", user.getId());
            return;
        }
        Instant now = Instant.now();
        boolean cooling = tokens.latestIssuedAt(user.getId())
                .map(t -> now.isBefore(t.plus(RESEND_COOLDOWN)))
                .orElse(false);
        if (cooling) {
            log.info("재설정 요청: 쿨다운 중 (무시) user={}", user.getId());
            return;
        }
        PasswordResetToken token = tokens.save(PasswordResetToken.issue(user.getId()));
        String link = consoleUrl + "/reset-password?token=" + token.getToken();
        try {
            sender.send(user.getEmail(), "[Outpace] 비밀번호 재설정 안내", resetMail(link), null, "Outpace", null);
            log.info("재설정 메일 발송: user={}", user.getId());
        } catch (Exception e) {
            // 발송 실패도 응답은 동일 — 로그로만 남긴다 (열거 방지 유지)
            log.error("재설정 메일 발송 실패: user={}", user.getId(), e);
        }
    }

    /** 토큰 검증 + 비밀번호 교체. 성공 시 토큰 소진 + 로그인 잠금 해제. */
    public void confirm(String tokenValue, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("비밀번호는 8자 이상이어야 합니다.");
        }
        Instant now = Instant.now();
        PasswordResetToken token = tokens.findByToken(tokenValue)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new IllegalArgumentException(
                        "재설정 링크가 만료됐거나 이미 사용됐어요. 다시 요청해주세요."));
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("계정을 찾을 수 없습니다."));

        user.setPasswordHash(hasher.hash(newPassword));
        users.save(user);
        token.markUsed(now);
        tokens.save(token);
        attempts.onSuccess(user.getEmail());   // 잊어서 틀리다 잠긴 계정의 복구 경로
        log.info("비밀번호 재설정 완료: user={}", user.getId());
    }

    private static String resetMail(String link) {
        return """
                <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
                  <h2 style="margin:0 0 12px">비밀번호 재설정</h2>
                  <p style="color:#555;line-height:1.7">아래 버튼을 눌러 새 비밀번호를 설정하세요.
                     이 링크는 <b>30분 동안, 한 번만</b> 쓸 수 있어요.</p>
                  <p style="margin:24px 0">
                    <a href="%s" style="background:#4f46e5;color:#fff;padding:12px 20px;
                       border-radius:8px;text-decoration:none;font-weight:700">새 비밀번호 설정</a>
                  </p>
                  <p style="color:#999;font-size:12px;line-height:1.6">본인이 요청하지 않았다면 이 메일은
                     무시하세요 — 비밀번호는 바뀌지 않습니다.<br>버튼이 안 눌리면 링크를 복사하세요: %s</p>
                </div>
                """.formatted(link, link);
    }
}
