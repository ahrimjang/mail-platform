package io.github.ahrimjang.mail.api.ops;

import io.github.ahrimjang.mail.core.service.PlatformOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 기동 시 {@code APP_PLATFORM_OPERATORS}(쉼표 구분 이메일)에 운영자 권한을 부여한다.
 * 멱등이고 회수는 하지 않는다 — 환경변수는 부트스트랩, 권한의 진실은 users.platform_role.
 * 아직 가입하지 않은 이메일은 경고만 남기고 다음 기동에서 다시 시도한다.
 */
@Component
public class PlatformOperatorSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformOperatorSeeder.class);

    private final PlatformOpsService ops;
    private final List<String> emails;

    public PlatformOperatorSeeder(PlatformOpsService ops,
                                  @Value("${app.platform.operators:}") String operators) {
        this.ops = ops;
        this.emails = operators == null || operators.isBlank()
                ? List.of()
                : Arrays.stream(operators.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (emails.isEmpty()) {
            return;
        }
        int granted = ops.seedOperators(emails);
        if (granted > 0) {
            log.info("플랫폼 운영자 {}명 부여 (APP_PLATFORM_OPERATORS)", granted);
        }
    }
}
