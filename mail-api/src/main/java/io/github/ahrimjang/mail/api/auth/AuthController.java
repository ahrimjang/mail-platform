package io.github.ahrimjang.mail.api.auth;

import io.github.ahrimjang.mail.common.AuthResponse;
import io.github.ahrimjang.mail.common.LoginRequest;
import io.github.ahrimjang.mail.common.SignupRequest;
import io.github.ahrimjang.mail.core.service.AuthService;
import io.github.ahrimjang.mail.core.service.TooManyLoginAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public API for user signup and login, returning a signed JWT on success.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Register a new user and return a freshly issued token. */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody SignupRequest request) {
        AuthResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Authenticate an existing user and return a freshly issued token. */
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, clientIp(http));
    }

    /**
     * 브루트포스 잠금의 IP 축 키. 프로드에서는 nginx 가 앞단이라 X-Forwarded-For 의
     * 첫 항목이 실제 클라이언트다(nginx.conf 가 심음). 헤더가 없으면 직접 연결 IP.
     */
    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<Map<String, String>> tooManyAttempts(TooManyLoginAttemptsException e) {
        long minutes = Math.max(1, (e.getRetryAfterSeconds() + 59) / 60);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(Map.of("error", "로그인 시도가 너무 많습니다. 약 " + minutes + "분 후 다시 시도하세요."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
