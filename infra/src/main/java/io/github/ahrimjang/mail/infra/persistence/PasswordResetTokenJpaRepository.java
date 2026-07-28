package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenJpaRepository extends JpaRepository<PasswordResetTokenEntity, Long> {

    Optional<PasswordResetTokenEntity> findByToken(String token);

    @Query("select max(t.createdAt) from PasswordResetTokenEntity t where t.userId = :userId")
    Optional<Instant> latestIssuedAt(@Param("userId") Long userId);
}
