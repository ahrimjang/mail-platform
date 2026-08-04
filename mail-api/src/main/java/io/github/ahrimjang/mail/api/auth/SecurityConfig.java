package io.github.ahrimjang.mail.api.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless security configuration: public auth + health endpoints, everything
 * else requires a valid JWT supplied via the {@link JwtAuthFilter}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/health", "/api/unsubscribe/**", "/api/track/**", "/api/webhooks/**").permitAll()
                        // 외부 구독 신청 — 인증은 X-Api-Key 로 서비스가 직접 검증
                        .requestMatchers("/api/public/**").permitAll()
                        // 요금제 목록: 가입 전 방문자가 보는 공개 페이지의 데이터
                        .requestMatchers("/api/plans").permitAll()
                        // uploaded template images: recipients' mail clients fetch these unauthenticated
                        .requestMatchers("/uploads/**").permitAll()
                        // Prometheus scrape (health + metrics only — see management.endpoints
                        // exposure). Production should firewall this path or move management
                        // to a separate internal port.
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                // Missing/expired JWT must read as 401 (unauthenticated), not Spring's
                // default 403 — the frontend keys its "force re-login" behavior on 401.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, e) -> response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
