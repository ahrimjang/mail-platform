package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.EmailVerificationToken;

import java.time.Instant;
import java.util.Optional;

/** 가입 이메일 인증 토큰 저장소 포트. */
public interface EmailVerificationTokenRepository {

    EmailVerificationToken save(EmailVerificationToken token);

    Optional<EmailVerificationToken> findByToken(String token);

    /** 재발송 쿨다운용 — 이 사용자의 가장 최근 발급 시각. */
    Optional<Instant> latestIssuedAt(Long userId);
}
