package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.EmailVerificationToken;
import io.github.ahrimjang.mail.core.port.EmailVerificationTokenRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** 어댑터: 가입 이메일 인증 토큰 포트의 JPA 구현. */
@Repository
public class JpaEmailVerificationTokenRepository implements EmailVerificationTokenRepository {

    private final EmailVerificationTokenJpaRepository jpa;

    public JpaEmailVerificationTokenRepository(EmailVerificationTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public EmailVerificationToken save(EmailVerificationToken token) {
        EmailVerificationTokenEntity saved = jpa.save(new EmailVerificationTokenEntity(
                token.getId(), token.getUserId(), token.getToken(),
                token.getExpiresAt(), token.getUsedAt(), token.getCreatedAt()));
        token.setId(saved.getId());
        return token;
    }

    @Override
    public Optional<EmailVerificationToken> findByToken(String token) {
        return jpa.findByToken(token).map(e -> {
            EmailVerificationToken t = new EmailVerificationToken();
            t.setId(e.getId());
            t.setUserId(e.getUserId());
            t.setToken(e.getToken());
            t.setExpiresAt(e.getExpiresAt());
            t.setUsedAt(e.getUsedAt());
            t.setCreatedAt(e.getCreatedAt());
            return t;
        });
    }

    @Override
    public Optional<Instant> latestIssuedAt(Long userId) {
        return jpa.latestIssuedAt(userId);
    }
}
