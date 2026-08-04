package io.github.ahrimjang.mail.api.auth;

import io.github.ahrimjang.mail.core.service.EmailVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 로그인한 사용자의 가입 이메일 인증 상태·재발송. /api/auth/** 와 달리 permitAll 이
 * 아니므로 Bearer 필수 — 재발송을 공개로 두면 남의 메일함 폭탄 통로가 된다.
 */
@RestController
@RequestMapping("/api/me/email-verification")
public class EmailVerificationController {

    private final EmailVerificationService verification;

    public EmailVerificationController(EmailVerificationService verification) {
        this.verification = verification;
    }

    /** 콘솔 배너의 표시 조건 — 현재 사용자의 인증 여부. */
    @GetMapping
    public Map<String, Boolean> status() {
        return Map.of("verified", verification.currentUserVerified());
    }

    /** 인증 메일 재발송 — 쿨다운 중이어도 항상 202 (서비스가 조용히 무시). */
    @PostMapping("/resend")
    public ResponseEntity<Void> resend() {
        verification.resendForCurrentUser();
        return ResponseEntity.accepted().build();
    }
}
