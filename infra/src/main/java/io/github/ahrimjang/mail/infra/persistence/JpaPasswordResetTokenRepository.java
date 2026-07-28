package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.PasswordResetToken;
import io.github.ahrimjang.mail.core.port.PasswordResetTokenRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** 어댑터: 재설정 토큰 포트의 JPA 구현. */
@Repository
public class JpaPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final PasswordResetTokenJpaRepository jpa;

    public JpaPasswordResetTokenRepository(PasswordResetTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PasswordResetToken save(PasswordResetToken token) {
        PasswordResetTokenEntity saved = jpa.save(new PasswordResetTokenEntity(
                token.getId(), token.getUserId(), token.getToken(),
                token.getExpiresAt(), token.getUsedAt(), token.getCreatedAt()));
        token.setId(saved.getId());
        return token;
    }

    @Override
    public Optional<PasswordResetToken> findByToken(String token) {
        return jpa.findByToken(token).map(e -> {
            PasswordResetToken t = new PasswordResetToken();
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
