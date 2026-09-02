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
    /** 개발 기본값 외에도 "누구나 아는 값"은 전부 막는다. */
    private static final java.util.Set<String> WEAK = java.util.Set.of(
            "maildb", "guest", "admin", "password", "postgres", "changeme", "secret", "test");
    /** 시크릿 최소 길이 — 이보다 짧으면 무차별 대입에 무력하다. */
    private static final int MIN_LENGTH = 16;

    private final String jwtSecret;
    private final String webhookSecret;
    private final String dbPassword;
    private final String rabbitPassword;
    private final boolean requireSecrets;

    public SecretsGuard(@Value("${app.jwt.secret:}") String jwtSecret,
                        @Value("${app.webhook.secret:}") String webhookSecret,
                        @Value("${spring.datasource.password:}") String dbPassword,
                        @Value("${spring.rabbitmq.password:}") String rabbitPassword,
                        @Value("${app.require-secrets:false}") boolean requireSecrets) {
        this.jwtSecret = jwtSecret;
        this.webhookSecret = webhookSecret;
        this.dbPassword = dbPassword;
        this.rabbitPassword = rabbitPassword;
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

    /** 안전하지 않은(기본값·약한 값·너무 짧은) 시크릿의 환경변수 이름 목록. */
    List<String> offenders() {
        List<String> offenders = new ArrayList<>();
        if (unsafe(jwtSecret, DEV_JWT)) {
            offenders.add("APP_JWT_SECRET");
        }
        if (unsafe(webhookSecret, DEV_WEBHOOK)) {
            offenders.add("APP_WEBHOOK_SECRET");
        }
        // DB·큐 비밀번호도 검사한다 — 운영에서 기본값(maildb/guest)이면 서버에 접근
        // 가능한 누구나 전 테넌트 데이터를 덤프할 수 있다(postgres 는 루프백 노출).
        if (unsafe(dbPassword, "maildb")) {
            offenders.add("DB_PASSWORD");
        }
        if (unsafe(rabbitPassword, "guest")) {
            offenders.add("RABBITMQ_PASSWORD");
        }
        return offenders;
    }

    /**
     * 기본값 일치뿐 아니라 공백 변형·잘 알려진 약한 값·짧은 값도 걸러낸다.
     * "기본값만 정확히 일치할 때"만 막으면 앞뒤 공백 하나로 우회된다.
     */
    private static boolean unsafe(String value, String devDefault) {
        if (value == null) {
            return false;   // 미주입(해당 앱이 안 쓰는 설정) — 검사 대상 아님
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return true;
        }
        return v.equals(devDefault) || WEAK.contains(v.toLowerCase()) || v.length() < MIN_LENGTH;
    }
}
