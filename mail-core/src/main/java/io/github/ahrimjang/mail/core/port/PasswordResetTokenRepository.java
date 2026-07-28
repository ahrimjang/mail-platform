package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.PasswordResetToken;

import java.time.Instant;
import java.util.Optional;

/** 비밀번호 재설정 토큰 저장소 포트. */
public interface PasswordResetTokenRepository {

    PasswordResetToken save(PasswordResetToken token);

    Optional<PasswordResetToken> findByToken(String token);

    /** 스팸 쿨다운용 — 이 사용자의 가장 최근 발급 시각. */
    Optional<Instant> latestIssuedAt(Long userId);
}
