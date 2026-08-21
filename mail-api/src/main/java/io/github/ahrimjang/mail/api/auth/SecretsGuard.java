package io.github.ahrimjang.mail.api.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 부팅 시 시크릿이 저장소에 평문으로 적힌 개발 기본값 그대로인지 검사한다.
 *
 * <p>설정을 {@code ${ENV:개발기본값}} 로 두는 원칙(로컬 무설정 동작) 때문에, 운영에서
 * {@code APP_JWT_SECRET} 한 줄만 누락·오타나면 공개된 기본 키로 조용히 기동해 임의 테넌트
 * JWT 위조가 가능해진다(AUDIT SEC-3). 그래서 기본값을 없애는 대신, 운영 표식
 * ({@code APP_REQUIRE_SECRETS=true}, 프로드 compose 가 설정)이 켜진 채 기본 시크릿이면
 * <b>기동을 실패</b>시킨다. 로컬(표식 없음)에서는 경고만 남기고 그대로 뜬다.
 */
@Component
public class SecretsGuard {

    private static final Logger log = LoggerFactory.getLogger(SecretsGuard.class);

    // application.yml 의 개발 기본값과 정확히 일치해야 한다(변경 시 함께 갱신).
    static final String DEV_JWT = "dev-only-not-a-real-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz";
    static final String DEV_WEBHOOK = "dev-webhook-secret";

    private final String jwtSecret;
    private final String webhookSecret;
    private final boolean requireSecrets;

    public SecretsGuard(@Value("${app.jwt.secret:}") String jwtSecret,
                        @Value("${app.webhook.secret:}") String webhookSecret,
                        @Value("${app.require-secrets:false}") boolean requireSecrets) {
        this.jwtSecret = jwtSecret;
        this.webhookSecret = webhookSecret;
        this.requireSecrets = requireSecrets;
    }

    @PostConstruct
    void check() {
        List<String> offenders = offenders();
        if (offenders.isEmpty()) {
            return;
        }
        String msg = "개발용 기본 시크릿이 그대로입니다: " + offenders + " — 운영에서는 반드시 교체하세요.";
        if (requireSecrets) {
            throw new IllegalStateException(msg + " (APP_REQUIRE_SECRETS=true 인데 기본값 사용 — 기동 중단)");
        }
        log.warn(msg);
    }

    /** 기본값 그대로인 시크릿의 환경변수 이름 목록. */
    List<String> offenders() {
        List<String> offenders = new ArrayList<>();
        if (DEV_JWT.equals(jwtSecret)) {
            offenders.add("APP_JWT_SECRET");
        }
        if (DEV_WEBHOOK.equals(webhookSecret)) {
            offenders.add("APP_WEBHOOK_SECRET");
        }
        return offenders;
    }
}
