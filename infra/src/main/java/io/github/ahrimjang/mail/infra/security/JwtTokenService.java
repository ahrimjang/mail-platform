package io.github.ahrimjang.mail.infra.security;

import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.TokenService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Adapter: implements the {@link TokenService} port using signed JWTs (jjwt 0.12.x).
 */
@Component
public class JwtTokenService implements TokenService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtTokenService(
            @Value("${app.jwt.secret:change-me-in-prod-please-use-a-long-random-secret-key-0123456789}") String secret,
            @Value("${app.jwt.expiration-minutes:120}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    @Override
    public String issue(User user) {
        Date now = Date.from(Instant.now());
        Date expiration = Date.from(Instant.now().plusSeconds(expirationMinutes * 60));
        return Jwts.builder()
                .subject(user.getEmail())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    @Override
    public Optional<String> verify(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Optional.of(subject);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
